package com.schecks.almin;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Streams BlueMap's loopback-only web app through the authenticated Almin origin. */
final class BlueMapProxy {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private static final Set<String> REQUEST_HEADERS = Set.of(
        "accept", "accept-encoding", "accept-language", "cache-control", "range",
        "if-match", "if-none-match", "if-modified-since", "if-unmodified-since");
    private static final Set<String> HOP_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "content-length");

    private BlueMapProxy() {}

    static void forward(HttpExchange ex, int port) {
        try {
            String rawPath = ex.getRequestURI().getRawPath();
            String path = rawPath.equals("/bluemap") ? "/"
                : rawPath.startsWith("/bluemap/") ? rawPath.substring("/bluemap".length()) : null;
            if (path == null || port <= 0 || port > 65535) {
                error(ex, 404, "BlueMap path not found.");
                return;
            }
            String query = ex.getRequestURI().getRawQuery();
            URI upstream = URI.create("http://127.0.0.1:" + port + path
                + (query == null ? "" : "?" + query));
            HttpRequest.Builder request = HttpRequest.newBuilder(upstream)
                .method(ex.getRequestMethod(), HttpRequest.BodyPublishers.noBody());
            ex.getRequestHeaders().forEach((name, values) -> {
                if (!REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) return;
                for (String value : values) request.header(name, value);
            });

            HttpResponse<InputStream> response = CLIENT.send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
            response.headers().map().forEach((name, values) -> {
                if (HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) return;
                for (String value : values) {
                    if (name.equalsIgnoreCase("location")) {
                        value = rewriteLocation(value, port);
                    }
                    ex.getResponseHeaders().add(name, value);
                }
            });
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "same-origin");

            int status = response.statusCode();
            boolean noBody = "HEAD".equals(ex.getRequestMethod()) || status == 204 || status == 304
                || (status >= 100 && status < 200);
            long length = response.headers().firstValueAsLong("content-length").orElse(0L);
            if (noBody) {
                ex.sendResponseHeaders(status, -1);
                response.body().close();
                return;
            }
            // A zero length selects chunked transfer. That is required for
            // BlueMap's SSE stream and harmless for an upstream without a size.
            ex.sendResponseHeaders(status, length > 0 ? length : 0);
            try (InputStream in = response.body(); OutputStream out = ex.getResponseBody()) {
                in.transferTo(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            quietlyError(ex, 502, "BlueMap proxy was interrupted.");
        } catch (IOException | RuntimeException e) {
            quietlyError(ex, 502, "BlueMap's web app is not answering: " + e.getMessage());
        } finally {
            try { ex.close(); } catch (RuntimeException ignored) {}
        }
    }

    private static String rewriteLocation(String value, int port) {
        String local = "http://127.0.0.1:" + port;
        if (value.startsWith(local + "/")) return "/bluemap/" + value.substring(local.length() + 1);
        if (value.startsWith("/")) return "/bluemap" + value;
        return value;
    }

    private static void quietlyError(HttpExchange ex, int status, String message) {
        try { error(ex, status, message); } catch (IOException ignored) {}
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        String safe = message == null ? "BlueMap is unavailable." : message;
        String body = "{\"error\":\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}
