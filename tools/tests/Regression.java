import com.schecks.almin.Passwords;
import com.schecks.almin.WebSessions;
import com.schecks.almin.WebFiles;
import com.schecks.almin.WebUi;
import com.schecks.almin.AlminConfig;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;

/** Regression cover for the guards that existed before the mods feature:
 *  password hashing, sessions + lockout, path traversal, and the web tiers. */
public class Regression {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    public static void main(String[] a) throws Exception {
        // ---- passwords ----
        String h = Passwords.hash("correct horse battery");
        ck("hash verifies", Passwords.verify("correct horse battery", h), "");
        ck("wrong password rejected", !Passwords.verify("wrong", h), "");
        ck("salted (two hashes differ)", !h.equals(Passwords.hash("correct horse battery")), "");
        ck("blank stored rejected", !Passwords.verify("x", ""), "");
        ck("malformed stored rejected", !Passwords.verify("x", "not$a$hash"), "");

        // ---- sessions + lockout ----
        WebSessions s = new WebSessions();
        String id = s.open(60);
        ck("session valid", s.valid(id), "");
        s.close(id);
        ck("closed session invalid", !s.valid(id), "");
        ck("expired session invalid", !s.valid(s.open(0)), "");
        WebSessions s2 = new WebSessions();
        for (int i = 0; i < s2.maxFailures(); i++) s2.recordFailure("ip");
        ck("locks out after max failures", s2.lockedOut("ip"), "");

        // ---- path traversal ----
        Path root = Files.createTempDirectory("alminroot");
        Files.createDirectories(root.resolve("config"));
        ck("normal path resolves", WebFiles.resolveUnder(root, "config/x.json") != null, "");
        ck("../ blocked", WebFiles.resolveUnder(root, "../secret") == null, "");
        ck("absolute blocked", WebFiles.resolveUnder(root, "/etc/passwd") == null, "");
        ck("nested escape blocked", WebFiles.resolveUnder(root, "config/../../root") == null, "");

        // ---- web tiers ----
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor(); cc.setAccessible(true);
        AlminConfig cfg = cc.newInstance();
        Field hf = AlminConfig.class.getDeclaredField("webAdminPasswordHash"); hf.setAccessible(true);
        hf.set(cfg, Passwords.hash("pw12345678"));
        Field inst = AlminConfig.class.getDeclaredField("instance"); inst.setAccessible(true); inst.set(null, cfg);

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        Constructor<WebUi> wc = WebUi.class.getDeclaredConstructor(
            HttpServer.class, java.util.concurrent.ExecutorService.class,
            net.minecraft.server.MinecraftServer.class, String.class, int.class);
        wc.setAccessible(true);
        int port = http.getAddress().getPort();
        WebUi ui = wc.newInstance(http, java.util.concurrent.Executors.newFixedThreadPool(2), null, "127.0.0.1", port);
        Field fj = WebUi.class.getDeclaredField("fullJson"); fj.setAccessible(true);
        fj.set(ui, "{\"rows\":[],\"secret\":\"only-when-authed\",\"generated\":1}");
        Field pj = WebUi.class.getDeclaredField("publicJson"); pj.setAccessible(true);
        pj.set(ui, "{\"rows\":[],\"generated\":1}");

        for (String[] r : new String[][]{{"/api/login","handleLogin"},{"/api/state","handleState"},
                {"/api/public","handlePublic"},{"/api/session","handleSession"},{"/api/exec","handleExec"}}) {
            Method m = WebUi.class.getDeclaredMethod(r[1], com.sun.net.httpserver.HttpExchange.class);
            m.setAccessible(true);
            http.createContext(r[0], ex -> { try { m.invoke(ui, ex); } catch (Exception e) { throw new RuntimeException(e); } });
        }
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        http.start();
        String base = "http://127.0.0.1:" + port;
        HttpClient anon = HttpClient.newHttpClient();
        HttpClient auth = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();

        ck("public tier open", get(anon, base + "/api/public").statusCode() == 200, "");
        ck("full state blocked pre-login", get(anon, base + "/api/state").statusCode() == 401, "");
        ck("terminal blocked pre-login",
            post(anon, base + "/api/exec", "{\"command\":\"say hi\"}").statusCode() == 401, "");
        ck("wrong password -> 401",
            post(auth, base + "/api/login", "{\"password\":\"nope\"}").statusCode() == 401, "");
        var ok = post(auth, base + "/api/login", "{\"password\":\"pw12345678\"}");
        ck("correct password -> 200", ok.statusCode() == 200, "" + ok.statusCode());
        ck("cookie is HttpOnly+SameSite",
            ok.headers().firstValue("set-cookie").orElse("").contains("HttpOnly")
            && ok.headers().firstValue("set-cookie").orElse("").contains("SameSite=Strict"), "");
        var st = get(auth, base + "/api/state");
        ck("full state after login -> 200", st.statusCode() == 200, "" + st.statusCode());
        ck("privileged data only after login", st.body().contains("only-when-authed"), "");

        http.stop(0);
        System.out.println(fail == 0 ? "\nREGRESSION TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static HttpResponse<String> get(HttpClient c, String u) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(u)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient c, String u, String b) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(u)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(b)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
