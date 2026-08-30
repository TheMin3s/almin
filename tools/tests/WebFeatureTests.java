import com.schecks.almin.AlminConfig;
import com.schecks.almin.Passwords;
import com.schecks.almin.WebFiles;
import com.schecks.almin.WebUi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Executors;

/**
 * The routes added so the web panel covers what the in-game GUI does:
 * settings, the password change, file transfer and the fetch endpoint.
 *
 * Driven over a real loopback socket against the real handlers, so the
 * auth gates, the allowlist and the write policy are all exercised for real.
 */
public class WebFeatureTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static String base;
    static String cookie = "";
    static AlminConfig cfg;

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        set("webAdminPasswordHash", Passwords.hash("pw12345678"));
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cfg);

        Path root = Files.createTempDirectory("alminweb");
        Files.createDirectories(root.resolve("mods"));

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        int port = http.getAddress().getPort();
        Constructor<WebUi> wc = WebUi.class.getDeclaredConstructor(
            HttpServer.class, java.util.concurrent.ExecutorService.class,
            net.minecraft.server.MinecraftServer.class, String.class, int.class);
        wc.setAccessible(true);
        WebUi ui = wc.newInstance(http, Executors.newFixedThreadPool(2), null, "127.0.0.1", port);

        for (String[] r : new String[][]{
                {"/api/login", "handleLogin"},
                {"/api/config", "handleConfig"},
                {"/api/config/reload", "handleConfigReload"},
                {"/api/password", "handlePassword"},
                {"/api/clearlog", "handleClearLog"},
                {"/api/players", "handlePlayers"},
                {"/api/mask", "handleMask"},
                {"/api/file/upload", "handleFileUpload"},
                {"/api/file/download", "handleFileDownload"},
                {"/api/activity", "handleActivity"},
                {"/api/track", "handleTrack"},
                {"/api/fetch", "handleFetch"}}) {
            Method m = WebUi.class.getDeclaredMethod(r[1], HttpExchange.class);
            m.setAccessible(true);
            // Through the same guard production uses: a route that throws must
            // answer, not drop the connection.
            Method g = WebUi.class.getDeclaredMethod("guard", String.class,
                com.sun.net.httpserver.HttpHandler.class);
            g.setAccessible(true);
            com.sun.net.httpserver.HttpHandler raw = ex -> {
                try { m.invoke(ui, ex); }
                catch (InvocationTargetException e) {
                    Throwable c = e.getCause();
                    if (c instanceof IOException io) throw io;
                    throw c instanceof RuntimeException re ? re : new RuntimeException(c);
                }
                catch (Exception e) { throw new RuntimeException(e); }
            };
            http.createContext(r[0], (com.sun.net.httpserver.HttpHandler) g.invoke(null, r[0], raw));
        }
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
        base = "http://127.0.0.1:" + port;

        gates();
        login();
        config();
        password();
        transfer();

        http.stop(0);
        System.out.println(fail == 0 ? "\nWEB-FEATURE TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void set(String field, Object v) throws Exception {
        Field f = AlminConfig.class.getDeclaredField(field);
        f.setAccessible(true); f.set(cfg, v);
    }
    static Object get(String field) throws Exception {
        Field f = AlminConfig.class.getDeclaredField(field);
        f.setAccessible(true); return f.get(cfg);
    }

    /** Nothing new may be reachable without a session. */
    static void gates() throws Exception {
        for (String[] r : new String[][]{
                {"GET", "/api/config"},
                {"GET", "/api/players"},
                {"GET", "/api/file/download?path=x"},
                {"GET", "/api/activity"},
                {"GET", "/api/track"}}) {
            var res = send(r[0], r[1], null, null);
            ck("anonymous " + r[1] + " -> 401", res.statusCode() == 401, String.valueOf(res.statusCode()));
        }
        for (String p : new String[]{"/api/config", "/api/config/reload", "/api/password",
                "/api/clearlog", "/api/mask", "/api/fetch", "/api/file/upload?path=mods/x",
                "/api/activity"}) {
            var res = send("POST", p, "{}", null);
            ck("anonymous POST " + p + " -> 401", res.statusCode() == 401, String.valueOf(res.statusCode()));
        }
    }

    static void login() throws Exception {
        var r = send("POST", "/api/login", "{\"password\":\"pw12345678\"}", null);
        ck("login succeeds", r.statusCode() == 200, r.body());
        cookie = r.headers().firstValue("set-cookie").orElse("").split(";")[0];
        ck("got a session cookie", cookie.startsWith("almin_session="), cookie);
    }

    static void config() throws Exception {
        var r = send("GET", "/api/config", null, cookie);
        ck("config lists the keys", r.statusCode() == 200 && r.body().contains("\"web-ui-port\""), r.body());
        ck("each key carries its type", r.body().contains("\"type\":\"BOOL\"")
            && r.body().contains("\"type\":\"INT\""), "");

        // The two locked keys must be marked, and refused.
        ck("web-start-command is marked locked", locked(r.body(), "web-start-command"), r.body());
        ck("the password hash is marked locked", locked(r.body(), "web-admin-password-hash"), "");
        ck("the hash value is never shipped",
            !r.body().contains(String.valueOf(get("webAdminPasswordHash"))), "hash present in body");

        var w = post("/api/config", "{\"name\":\"web-start-command\",\"value\":\"rm -rf /\"}");
        ck("setting web-start-command -> 403", w.statusCode() == 403, w.body());
        ck("...and it is untouched", "".equals(get("webStartCommand")), String.valueOf(get("webStartCommand")));

        w = post("/api/config", "{\"name\":\"web-admin-password-hash\",\"value\":\"forged\"}");
        ck("setting the hash -> 403", w.statusCode() == 403, w.body());

        w = post("/api/config", "{\"name\":\"nope\",\"value\":\"1\"}");
        ck("unknown key -> 400", w.statusCode() == 400, w.body());

        w = post("/api/config", "{\"name\":\"web-session-minutes\",\"value\":\"1\"}");
        ck("out-of-range int -> 400", w.statusCode() == 400, w.body());
        ck("...and the value survives", (Integer) get("webSessionMinutes") == 120,
            String.valueOf(get("webSessionMinutes")));

        w = post("/api/config", "{\"name\":\"web-session-minutes\",\"value\":\"90\"}");
        ck("valid int is accepted", w.statusCode() == 200, w.body());
        ck("...and applied", (Integer) get("webSessionMinutes") == 90, String.valueOf(get("webSessionMinutes")));

        w = post("/api/config", "{\"name\":\"spawn-immunity-seconds\",\"value\":\"true\"}");
        ck("a word where a number goes -> 400", w.statusCode() == 400, w.body());

        w = post("/api/config", "{\"name\":\"mods-advertise\",\"value\":\"false\"}");
        ck("a bool toggles", w.statusCode() == 200 && Boolean.FALSE.equals(get("modsAdvertise")), w.body());

        // The panel isn't running in this harness, so this must not try to bounce it.
        w = post("/api/config", "{\"name\":\"web-ui-port\",\"value\":\"8123\"}");
        ck("port change is saved", w.statusCode() == 200, w.body());
        ck("...without claiming a restart it can't do", !w.body().contains("panelRestarting"), w.body());

        // The activity log is behind the same login, and only "clear" is an action.
        var act = send("GET", "/api/activity", null, cookie);
        ck("activity reads for an admin", act.statusCode() == 200 && act.body().contains("\"rows\""),
            act.statusCode() + " " + act.body());
        ck("...and reports its retention", act.body().contains("\"retentionMinutes\""), act.body());
        var bad = post("/api/activity", "{\"action\":\"export\"}");
        ck("an unknown activity action -> 400", bad.statusCode() == 400, bad.body());

        // The map's data is behind the same login, and lists who has a track.
        var tr = send("GET", "/api/track", null, cookie);
        ck("the track route answers", tr.statusCode() == 200 && tr.body().contains("\"players\""),
            tr.statusCode() + " " + tr.body());
        ck("...and reports the sample interval", tr.body().contains("\"trackSeconds\""), tr.body());
        var one = send("GET", "/api/track?player=Nobody", null, cookie);
        ck("an unknown player gives an empty track",
            one.statusCode() == 200 && one.body().contains("\"points\":[]"), one.body());

        var rl = post("/api/config/reload", "{}");
        ck("reload answers", rl.statusCode() == 200 || rl.statusCode() == 409, rl.body());
    }

    static boolean locked(String body, String key) {
        int i = body.indexOf("\"name\":\"" + key + "\"");
        if (i < 0) return false;
        int end = body.indexOf("}", i);
        return body.substring(i, end).contains("\"editable\":false");
    }

    static void password() throws Exception {
        var r = post("/api/password", "{\"password\":\"short\"}");
        ck("a short password -> 400", r.statusCode() == 400, r.body());

        String before = (String) get("webAdminPasswordHash");
        r = post("/api/password", "{\"password\":\"a-longer-one\"}");
        ck("a real password is accepted", r.statusCode() == 200, r.body());
        ck("the stored hash changed", !before.equals(get("webAdminPasswordHash")), "");
        ck("it is stored hashed, not in the clear",
            !String.valueOf(get("webAdminPasswordHash")).contains("a-longer-one"), "");
        ck("the new password verifies",
            Passwords.verify("a-longer-one", (String) get("webAdminPasswordHash")), "");

        // The caller is re-issued a session rather than being cut off.
        String fresh = r.headers().firstValue("set-cookie").orElse("");
        ck("the caller gets a fresh session", fresh.startsWith("almin_session="), fresh);
        String newCookie = fresh.split(";")[0];
        var still = send("GET", "/api/config", null, newCookie);
        ck("...and it works", still.statusCode() == 200, String.valueOf(still.statusCode()));

        // Every other session is gone.
        var old = send("GET", "/api/config", null, cookie);
        ck("the old session is signed out", old.statusCode() == 401, String.valueOf(old.statusCode()));
        cookie = newCookie;
    }

    /** Upload, download and fetch all sit behind the ordinary write policy. */
    static void transfer() throws Exception {
        // There is no live server here, so these routes fault. What matters is
        // that a fault becomes an answer: an unanswered exchange looks exactly
        // like a dropped upload in a browser, which is how the real bug hid.
        var r = send("POST", "/api/file/upload?path=mods/x.jar", "data", cookie);
        ck("a faulting upload still answers", r.statusCode() >= 400 && r.statusCode() < 600,
            r.statusCode() + " " + r.body());
        ck("...with a reason, not an empty body", r.body().contains("error"), r.body());

        r = send("GET", "/api/file/download?path=mods/x.jar", null, cookie);
        ck("a faulting download still answers", r.statusCode() >= 400 && r.statusCode() < 600,
            r.statusCode() + " " + r.body());

        r = post("/api/fetch", "{\"url\":\"https://example.invalid/x.jar\",\"dest\":\"mods/\"}");
        ck("a faulting fetch still answers", r.statusCode() >= 400 && r.statusCode() < 600,
            r.statusCode() + " " + r.body());

        // And the connection survives it — the next request must work.
        var after = send("GET", "/api/config", null, cookie);
        ck("the panel keeps working after a route faults", after.statusCode() == 200,
            String.valueOf(after.statusCode()));

        r = post("/api/fetch", "{\"url\":\"\",\"dest\":\"mods/\"}");
        ck("fetch with no url is refused", r.statusCode() != 200, r.statusCode() + " " + r.body());

        r = send("POST", "/api/file/upload", "data", cookie);
        ck("upload with no path is refused", r.statusCode() != 200, String.valueOf(r.statusCode()));

        // The policy itself, exercised directly — the part a live server would use.
        Path root = Files.createTempDirectory("alminpolicy");
        ck("traversal is refused by the shared resolver",
            WebFiles.resolveUnder(root, "../../etc/passwd") == null, "");
        ck("the upload cap is a real number", WebFiles.MAX_UPLOAD_BYTES > 0, "");

        var mask = post("/api/mask", "{\"name\":\"\",\"mask\":\"x\"}");
        ck("a mask with no player is refused", mask.statusCode() == 400 || mask.statusCode() == 409,
            mask.statusCode() + " " + mask.body());
    }

    // ---- plumbing ----
    static HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body, cookie);
    }
    static HttpResponse<String> send(String method, String path, String body, String ck) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json");
        if (ck != null && !ck.isEmpty()) b.header("Cookie", ck);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                      : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
