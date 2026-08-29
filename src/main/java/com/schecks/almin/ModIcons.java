package com.schecks.almin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

/**
 * The picture next to a mod's name in the panel.
 *
 * <h3>Where a picture comes from</h3>
 * Two sources, in this order:
 * <ol>
 *   <li><b>The jar itself.</b> A Fabric jar names its own icon in
 *       {@code fabric.mod.json}, so a mod this server hosts already carries
 *       one. Nothing is fetched, nothing is stored, and it works on a server
 *       with no route to the internet at all.</li>
 *   <li><b>Modrinth, cached to disk.</b> When a mod is added from Modrinth the
 *       project's icon is downloaded once into
 *       {@code config/almin/modicons/} and served from there afterwards.</li>
 * </ol>
 *
 * <h3>Why cache instead of linking</h3>
 * A {@code <img src="https://cdn.modrinth.com/...">} in the panel would be
 * simpler, and it would also mean every admin who opens the Mods tab makes a
 * request to a third party, from their own browser, listing what this server
 * runs. Fetching once on the server keeps that between Almin and Modrinth, and
 * it means the tab still has icons on a machine whose browser cannot reach the
 * internet — a locked-down box reached over a tunnel, which is exactly the
 * setup someone running a panel on a rented server has.
 *
 * <h3>Care taken</h3>
 * The download host is pinned to Modrinth's CDN, so a mangled or hostile
 * {@code icon_url} cannot turn this into a way of making the server fetch
 * arbitrary addresses. The response is capped, and the first bytes must
 * actually be an image — the content type on the wire is not trusted for that.
 */
public final class ModIcons {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Modrinth icons are tens of kilobytes; this is generous for one. */
    private static final long MAX_BYTES = 1024 * 1024;

    /** The only host an icon is ever fetched from. */
    private static final String CDN_HOST = "cdn.modrinth.com";

    private static volatile Path dir;

    private ModIcons() {}

    /** A picture and the content type to serve it as. */
    public record Icon(byte[] bytes, String contentType) {}

    /** Point at {@code config/almin/modicons/}. Call once at server start. */
    public static synchronized void init(Path alminDir) {
        if (alminDir == null) return;
        dir = alminDir.resolve("modicons");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not create modicons/ folder: {}", e.getMessage());
        }
    }

    /** Where cached icons live, for tests and for the file browser. */
    public static Path dir() {
        return dir;
    }

    /**
     * The icon for one advertised mod, or null when there isn't one.
     *
     * <p>The jar wins over the cache: it is the mod's own picture, it cannot
     * go stale, and it is already on this disk.
     */
    public static Icon forMod(ModOffers.AdvertisedMod mod) {
        if (mod == null) return null;
        if (mod.serverHosted()) {
            Path jar = ModOffers.resolveModFile(mod.file());
            if (jar != null) {
                ModJars.Meta meta = ModJars.read(jar);
                if (meta.ok() && !meta.icon().isBlank()) {
                    byte[] bytes = ModJars.entry(jar, meta.icon());
                    String type = sniff(bytes);
                    if (type != null) return new Icon(bytes, type);
                }
            }
        }
        return cached(mod.modId());
    }

    /**
     * Whether {@link #forMod} would find something, without decompressing it.
     *
     * <p>The mod list asks this once per mod purely to decide whether to put
     * an {@code <img>} on the page, and pulling every icon out of every jar to
     * answer a yes/no would be a lot of work for a boolean.
     */
    public static boolean exists(ModOffers.AdvertisedMod mod) {
        if (mod == null) return false;
        Path file = fileFor(mod.modId());
        if (file != null && Files.isRegularFile(file)) return true;
        if (!mod.serverHosted()) return false;
        Path jar = ModOffers.resolveModFile(mod.file());
        if (jar == null) return false;
        ModJars.Meta meta = ModJars.read(jar);
        return meta.ok() && !meta.icon().isBlank();
    }

    /** A previously downloaded icon for {@code modId}, or null. */
    public static Icon cached(String modId) {
        Path file = fileFor(modId);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            if (Files.size(file) > MAX_BYTES) return null;
            byte[] bytes = Files.readAllBytes(file);
            String type = sniff(bytes);
            return type == null ? null : new Icon(bytes, type);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Downloads {@code url} and keeps it as {@code modId}'s icon.
     *
     * <p>Best effort by design: a mod with no picture is a cosmetic loss, and
     * failing the add that produced it would not be. Blocks on the network, so
     * never call it from the server thread.
     */
    public static void fetch(String modId, String url) {
        Path file = fileFor(modId);
        if (file == null || url == null || url.isBlank()) return;
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || !CDN_HOST.equalsIgnoreCase(uri.getHost())) {
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", "TheMin3s/almin (Minecraft server admin mod)")
                .timeout(TIMEOUT)
                .GET()
                .build();
            byte[] bytes;
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()) {
                HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) return;
                bytes = response.body();
            }
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return;
            if (sniff(bytes) == null) return;
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".icon-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // No icon is a picture that isn't there, not a failure to report.
        }
    }

    /** Removes a cached icon, when its mod stops being advertised. */
    public static void forget(String modId) {
        Path file = fileFor(modId);
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {}
    }

    /**
     * The cache file for a mod id, or null if the id could name anything but a
     * file. Mod ids are {@code [a-z0-9_-]} by Fabric's own rules; this is the
     * same shape, and it is what keeps an id out of the rest of the disk.
     */
    static Path fileFor(String modId) {
        if (dir == null || modId == null) return null;
        String id = modId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.length() > 64 || !id.matches("[a-z0-9_.-]+")) return null;
        if (id.startsWith(".")) return null;
        Path file = dir.resolve(id + ".img").toAbsolutePath().normalize();
        return file.startsWith(dir.toAbsolutePath().normalize()) ? file : null;
    }

    /**
     * The image format these bytes actually are, or null.
     *
     * <p>Read from the bytes rather than from the {@code Content-Type} header,
     * because the header is written by whoever sent the file and the panel is
     * about to hand the result to a browser.
     */
    static String sniff(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        // SVG is deliberately absent: it is a document a browser executes, and
        // this serves it from the panel's own origin.
        return null;
    }
}
