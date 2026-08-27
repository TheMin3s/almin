package com.schecks.almin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTPS file fetcher backing /almin op fetch.
 *
 * Guardrails:
 *  - HTTPS-only.
 *  - Destination path must, after normalisation, sit under the server root AND
 *    either start with one of the ALLOWED_PREFIXES directories or sit under a
 *    "&lt;level&gt;/datapacks/" folder.
 *  - 100 MB hard cap; 30s connect timeout; 5min total timeout.
 *  - Streams to a temp file in the destination directory, then atomic-moves
 *    into place — so a partial download never leaves a half-written mod jar.
 */
public final class FileFetcher {
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    public static final Set<String> ALLOWED_PREFIXES =
        Set.of("mods", "config", "datapacks", "resourcepacks");

    private FileFetcher() {}

    public record FetchResult(boolean ok, long bytes, String message, Path destination) {
        public static FetchResult fail(String msg) {
            return new FetchResult(false, 0, msg, null);
        }
        public static FetchResult failAt(long bytes, String msg) {
            return new FetchResult(false, bytes, msg, null);
        }
    }

    public static CompletableFuture<FetchResult> fetchAsync(String url, Path destination, Path serverRoot) {
        return CompletableFuture.supplyAsync(() -> fetch(url, destination, serverRoot));
    }

    public static FetchResult fetch(String url, Path destination, Path serverRoot) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return FetchResult.fail("Invalid URL: " + e.getMessage());
        }
        if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return FetchResult.fail("Only https:// URLs are allowed (got: " + uri.getScheme() + ")");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return FetchResult.fail("URL missing host");
        }

        Path absRoot = serverRoot.toAbsolutePath().normalize();
        Path normalised = destination.toAbsolutePath().normalize();
        if (!normalised.startsWith(absRoot)) {
            return FetchResult.fail("Destination escapes server directory");
        }
        Path rel = absRoot.relativize(normalised);
        if (rel.getNameCount() == 0) {
            return FetchResult.fail("Empty destination path");
        }
        String firstSegment = rel.getName(0).toString();
        // Datapacks live in <level>/datapacks/, so the first segment is the
        // (variable) level-folder name — allow any path whose second segment
        // is "datapacks" in addition to the fixed prefix list.
        boolean underDatapacks = rel.getNameCount() >= 3
            && rel.getName(1).toString().equals("datapacks");
        if (!ALLOWED_PREFIXES.contains(firstSegment) && !underDatapacks) {
            return FetchResult.fail("Destination must start with one of " + ALLOWED_PREFIXES
                + ", or sit under a <level>/datapacks/ folder");
        }
        if (normalised.getFileName() == null || normalised.getFileName().toString().isBlank()) {
            return FetchResult.fail("Destination missing filename");
        }

        Path parent = normalised.getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            return FetchResult.fail("Cannot create destination dir: " + e.getMessage());
        }

        Path tmp;
        try {
            tmp = Files.createTempFile(parent, ".almin-dl-", ".part");
        } catch (IOException e) {
            return FetchResult.fail("Cannot create temp file: " + e.getMessage());
        }

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

        HttpRequest req = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", "almin/1.0 (+fabric)")
            .GET()
            .build();

        long bytes = 0;
        try {
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                deleteQuietly(tmp);
                return FetchResult.fail("HTTP " + resp.statusCode());
            }
            // Reject redirects that downgraded to http (HttpClient.Redirect.NORMAL already enforces this,
            // but double-check the final URI just in case).
            URI finalUri = resp.uri();
            if (!"https".equalsIgnoreCase(finalUri.getScheme())) {
                deleteQuietly(tmp);
                return FetchResult.fail("Redirected to non-https URL: " + finalUri);
            }
            try (InputStream in = resp.body();
                 OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bytes += n;
                    if (bytes > MAX_BYTES) {
                        deleteQuietly(tmp);
                        return FetchResult.failAt(bytes, "Exceeded max size " + MAX_BYTES + " bytes");
                    }
                    out.write(buf, 0, n);
                }
            }
            try {
                Files.move(tmp, normalised, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                // Some filesystems don't support ATOMIC_MOVE — fall back to a regular move.
                Files.move(tmp, normalised, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            deleteQuietly(tmp);
            return FetchResult.failAt(bytes, "Fetch failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return new FetchResult(true, bytes, "OK", normalised);
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }

    /**
     * Extracts the last path segment of a URL to use as a filename.
     * Rejects empty, traversing, or slash-containing results.
     */
    public static String basenameFromUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path == null) return null;
            int slash = path.lastIndexOf('/');
            String name = (slash >= 0) ? path.substring(slash + 1) : path;
            if (name.isBlank() || name.contains("..") || name.contains("/") || name.contains("\\")) return null;
            return name;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
