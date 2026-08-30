import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback wire checks for the authenticated BlueMap streaming primitive. */
public class BlueMapProxyTests {
    static int failures;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + label
            + (ok ? "" : " -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<String> requested = new AtomicReference<>("");
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        upstream.createContext("/maps/world/tile", ex -> {
            requested.set(ex.getRequestURI().toString());
            byte[] body = new byte[] {0, 1, 2, 3, 4, 5};
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("ETag", "\"tile-1\"");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        int upstreamPort = upstream.getAddress().getPort();
        upstream.createContext("/go", ex -> {
            ex.getResponseHeaders().set("Location",
                "http://127.0.0.1:" + upstreamPort + "/maps/world/tile");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        upstream.createContext("/live/sse", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("event: marker\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        ExecutorService upstreamPool = Executors.newFixedThreadPool(2);
        upstream.setExecutor(upstreamPool);
        upstream.start();

        Class<?> proxyType = Class.forName("com.schecks.almin.BlueMapProxy");
        Method forward = proxyType.getDeclaredMethod("forward", HttpExchange.class, int.class);
        forward.setAccessible(true);
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        proxy.createContext("/bluemap", ex -> {
            try {
                forward.invoke(null, ex, upstreamPort);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ExecutorService proxyPool = Executors.newFixedThreadPool(3);
        proxy.setExecutor(proxyPool);
        proxy.start();

        try {
            int proxyPort = proxy.getAddress().getPort();
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<byte[]> tile = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + proxyPort + "/bluemap/maps/world/tile?x=4&z=-2"))
                .header("Accept", "application/octet-stream").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            check("the proxy preserves binary tile bytes and content type",
                tile.statusCode() == 200 && tile.body().length == 6 && tile.body()[5] == 5
                    && tile.headers().firstValue("content-type").orElse("")
                        .equals("application/octet-stream"),
                tile.statusCode() + " / " + tile.body().length);
            check("the proxy strips only its /bluemap prefix and keeps the query",
                "/maps/world/tile?x=4&z=-2".equals(requested.get()), requested.get());

            HttpResponse<String> redirect = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + proxyPort + "/bluemap/go")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            check("loopback redirects are rewritten onto the authenticated origin",
                redirect.statusCode() == 302 && redirect.headers().firstValue("location")
                    .orElse("").equals("/bluemap/maps/world/tile"),
                redirect.headers().firstValue("location").orElse("missing"));

            HttpResponse<String> sse = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + proxyPort + "/bluemap/live/sse")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            check("chunked BlueMap SSE passes through without buffering into JSON",
                sse.statusCode() == 200 && sse.body().contains("event: marker")
                    && sse.headers().firstValue("content-type").orElse("")
                        .equals("text/event-stream"), sse.statusCode() + " / " + sse.body());
        } finally {
            proxy.stop(0);
            upstream.stop(0);
            proxyPool.shutdownNow();
            upstreamPool.shutdownNow();
        }

        System.out.println(failures == 0 ? "\nBLUEMAP PROXY TESTS PASSED"
            : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
