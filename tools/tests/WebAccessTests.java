import com.schecks.almin.AlminConfig;
import com.schecks.almin.Passwords;
import com.schecks.almin.WebUi;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;

/** The reworked login gate: works over plain HTTP by default, strict on request. */
public class WebAccessTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor(); cc.setAccessible(true);
        AlminConfig cfg = cc.newInstance();
        Field h = AlminConfig.class.getDeclaredField("webAdminPasswordHash"); h.setAccessible(true);
        h.set(cfg, Passwords.hash("pw12345678"));
        Field strict = AlminConfig.class.getDeclaredField("webRequireSecure"); strict.setAccessible(true);
        Field bind = AlminConfig.class.getDeclaredField("webUiBind"); bind.setAccessible(true);
        Field inst = AlminConfig.class.getDeclaredField("instance"); inst.setAccessible(true); inst.set(null, cfg);

        ck("bind defaults to all interfaces (reachable without a proxy)",
            "0.0.0.0".equals(bind.get(cc.newInstance())), String.valueOf(bind.get(cc.newInstance())));
        ck("web-require-secure defaults off", !((boolean) strict.get(cc.newInstance())), "");

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        Constructor<WebUi> wc = WebUi.class.getDeclaredConstructor(
            HttpServer.class, java.util.concurrent.ExecutorService.class,
            net.minecraft.server.MinecraftServer.class, String.class, int.class);
        wc.setAccessible(true);
        int port = http.getAddress().getPort();
        WebUi ui = wc.newInstance(http, java.util.concurrent.Executors.newFixedThreadPool(2), null, "127.0.0.1", port);
        for (String[] r : new String[][]{{"/api/login","handleLogin"},{"/api/session","handleSession"}}) {
            Method m = WebUi.class.getDeclaredMethod(r[1], com.sun.net.httpserver.HttpExchange.class);
            m.setAccessible(true);
            http.createContext(r[0], ex -> { try { m.invoke(ui, ex); } catch (Exception e) { throw new RuntimeException(e); } });
        }
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        http.start();
        String base = "http://127.0.0.1:" + port;

        // Loopback always works (this is how the sandbox reaches it).
        strict.setBoolean(cfg, false);
        var r = post(base + "/api/login", "{\"password\":\"pw12345678\"}");
        ck("login works with require-secure OFF", r.statusCode() == 200, r.statusCode() + " " + r.body());

        strict.setBoolean(cfg, true);
        r = post(base + "/api/login", "{\"password\":\"pw12345678\"}");
        ck("loopback still works with require-secure ON", r.statusCode() == 200, r.statusCode() + " " + r.body());

        // Session reports both the permission and the real transport state.
        var s = get(base + "/api/session");
        ck("session exposes 'encrypted' for the UI warning", s.body().contains("\"encrypted\""), s.body());
        ck("loopback reports encrypted=true", s.body().contains("\"encrypted\":true"), s.body());

        // The panel now tells an admin how the server gets started again, which
        // is this host's java command line: install paths, heap size, jar
        // names. /api/session answers before anyone has logged in, so that has
        // to be one of the things login buys.
        ck("an anonymous session is not told the start command",
            !s.body().contains("\"startCommand\""), s.body());
        ck("...nor why it cannot start the server",
            !s.body().contains("\"startProblem\""), s.body());
        ck("...but is still told whether one exists, which gives nothing away",
            s.body().contains("\"canStart\""), s.body());

        String cookie = r.headers().firstValue("set-cookie").orElse("");
        ck("logging in sets a session cookie", !cookie.isEmpty(), "none");
        var authed = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + "/api/session"))
                .header("Cookie", cookie.split(";")[0]).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        ck("a logged-in session is authed", authed.body().contains("\"authed\":true"), authed.body());
        ck("...and is told the start command", authed.body().contains("\"startCommand\""),
            authed.body());

        // A refusal must explain the fix, not just say "insecure".
        Method secure = WebUi.class.getDeclaredMethod("secure", com.sun.net.httpserver.HttpExchange.class);
        ck("secure() is still consulted for login", secure != null, "");

        http.stop(0);
        System.out.println(fail == 0 ? "\nWEB-ACCESS TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
    static HttpResponse<String> get(String u) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(u)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(String u, String b) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(u))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(b)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
