import com.schecks.almin.AlminConfig;
import com.schecks.almin.Passwords;
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
 * The routes the redesigned panel added: player faces, mod icons, folder
 * creation, and the extra fields the browser and the mod list now read.
 *
 * Over a real loopback socket against the real handlers, so the auth gates
 * and the write policy are exercised rather than described.
 */
public class PanelApiTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static String base, cookie = "";
    static AlminConfig cfg;
    static Path root;

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        set("webAdminPasswordHash", Passwords.hash("pw12345678"));
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cfg);

        root = Files.createTempDirectory("alminpanel");
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
                {"/api/server", "handleServerControl"},
                {"/api/state", "handleState"},
                {"/api/session", "handleSession"},
                {"/api/head", "handleHead"},
                {"/api/mods", "handleMods"},
                {"/api/mods/icon", "handleModIcon"},
                {"/api/file/mkdir", "handleFileMkdir"},
                {"/api/files", "handleFiles"}}) {
            Method m = WebUi.class.getDeclaredMethod(r[1], HttpExchange.class);
            m.setAccessible(true);
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
            http.createContext(r[0], (com.sun.net.httpserver.HttpHandler) g.invoke(ui, r[0], raw));
        }
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
        base = "http://127.0.0.1:" + port;

        gates();
        login();
        controls();
        heads();
        modIcons();
        mkdir();
        mkdirRules();
        listings();

        http.stop(0);
        System.out.println(fail == 0 ? "\nPANEL-API TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void set(String field, Object v) throws Exception {
        Field f = AlminConfig.class.getDeclaredField(field);
        f.setAccessible(true); f.set(cfg, v);
    }

    /** Which UUIDs a server knows is not public information. */
    static void gates() throws Exception {
        for (String p : new String[]{
                "/api/head?uuid=516e51d9-4e6b-4a2f-a282-e0f51f5a20e7",
                "/api/mods",
                "/api/mods/icon?id=sodium"}) {
            var r = send("GET", p, null, null);
            ck("anonymous " + p + " -> 401", r.statusCode() == 401, String.valueOf(r.statusCode()));
        }
        var r = send("POST", "/api/file/mkdir", "{\"path\":\"mods\",\"name\":\"x\"}", null);
        ck("anonymous mkdir -> 401", r.statusCode() == 401, String.valueOf(r.statusCode()));
        ck("...and made nothing", !Files.exists(root.resolve("mods/x")), "it created the folder");
    }

    static void login() throws Exception {
        var r = send("POST", "/api/login", "{\"password\":\"pw12345678\"}", null);
        ck("login succeeds", r.statusCode() == 200, r.body());
        cookie = r.headers().firstValue("set-cookie").orElse("").split(";")[0];
    }

    /**
     * The owner is not refused by the permission gate.
     *
     * <p>Starting and restarting go through the same route table as every
     * other menu now, so a mistake there stops the server being startable
     * from the panel — which is the one thing the panel has to be able to do
     * when the server is down.
     */
    static void controls() throws Exception {
        for (String action : new String[]{"start", "restart", "stop"}) {
            var r = send("POST", "/api/server", "{\"action\":\"" + action + "\"}", cookie);
            ck("the main account is not refused " + action + " (" + r.statusCode() + ")",
                r.statusCode() != 401 && r.statusCode() != 403, r.body());
        }
        var st = send("GET", "/api/state", null, cookie);
        ck("...nor the state the header reads (" + st.statusCode() + ")",
            st.statusCode() != 401 && st.statusCode() != 403, st.body());
        var out = send("POST", "/api/server", "{\"action\":\"start\"}", null);
        ck("a stranger still is", out.statusCode() == 401, out.body());
    }

    static void heads() throws Exception {
        var bad = send("GET", "/api/head?uuid=not-a-uuid", null, cookie);
        ck("a malformed uuid is a 400, not a lookup", bad.statusCode() == 400, bad.body());

        var none = send("GET", "/api/head?uuid=" + URLEncoder("../../etc/passwd"), null, cookie);
        ck("a path is not a uuid either", none.statusCode() == 400, none.body());

        // Off means off: no request goes out and nothing is served.
        set("webPlayerHeads", Boolean.FALSE);
        var off = send("GET", "/api/head?uuid=516e51d9-4e6b-4a2f-a282-e0f51f5a20e7", null, cookie);
        ck("web-player-heads off refuses the route", off.statusCode() == 404, off.body());
        ck("...and says why", off.body().contains("web-player-heads"), off.body());

        // Back on, but with no Minecraft server and an offline uuid, the
        // lookup must still answer quickly rather than hold the panel.
        set("webPlayerHeads", Boolean.TRUE);
        long start = System.currentTimeMillis();
        var on = send("GET", "/api/head?uuid=00000000-0000-0000-0000-000000000001", null, cookie);
        long took = System.currentTimeMillis() - start;
        ck("an unknown player answers rather than hanging",
            on.statusCode() == 404 || on.statusCode() == 200, String.valueOf(on.statusCode()));
        ck("...and answers promptly (" + took + "ms)", took < 5000, took + "ms");

        // The session says whether to ask at all.
        var s = send("GET", "/api/session", null, cookie);
        ck("the session tells the page faces are on", s.body().contains("\"heads\":true"), s.body());
        set("webPlayerHeads", Boolean.FALSE);
        var s2 = send("GET", "/api/session", null, cookie);
        ck("...and tells it when they are off", s2.body().contains("\"heads\":false"), s2.body());
        var anon = send("GET", "/api/session", null, null);
        ck("a logged-out session is told nothing about them",
            !anon.body().contains("\"heads\""), anon.body());
        set("webPlayerHeads", Boolean.TRUE);
    }

    static void modIcons() throws Exception {
        var missing = send("GET", "/api/mods/icon?id=nothing-here", null, cookie);
        ck("no icon is a 404, not an empty image", missing.statusCode() == 404, missing.body());
        var evil = send("GET", "/api/mods/icon?id=" + URLEncoder("../../../etc/passwd"), null, cookie);
        ck("an id that is a path gets nothing", evil.statusCode() == 404, evil.body());
    }

    static void mkdir() throws Exception {
        var ok = send("POST", "/api/file/mkdir", "{\"path\":\"mods\",\"name\":\"packs\"}", cookie);
        // No server behind this handler, so the write itself cannot complete;
        // what matters is that it answers rather than throwing.
        ck("mkdir answers", ok.statusCode() == 200 || ok.statusCode() == 400
            || ok.statusCode() == 503, String.valueOf(ok.statusCode()));

        for (String name : new String[]{"..", "a/b", "a\\\\b", "", "  "}) {
            var r = send("POST", "/api/file/mkdir",
                "{\"path\":\"mods\",\"name\":\"" + name + "\"}", cookie);
            ck("mkdir refuses the name '" + name + "'", r.statusCode() != 200,
                String.valueOf(r.statusCode()));
        }
        ck("nothing escaped the server directory",
            !Files.exists(root.getParent().resolve("b")), "something was created outside");
    }

    /** The folder rules, against a real directory, with no server in the way. */
    static void mkdirRules() throws Exception {
        Path box = Files.createTempDirectory("alminmk");
        Files.createDirectories(box.resolve("mods"));
        Files.createDirectories(box.resolve("logs"));
        Files.createDirectories(box.resolve("world/datapacks"));
        java.util.Set<String> roots = java.util.Set.of("mods", "config");
        Path datapacks = box.resolve("world/datapacks").toAbsolutePath().normalize();

        var ok = com.schecks.almin.WebFiles.mkdir(box, roots, datapacks, "mods", "packs");
        ck("a folder under a writable root is created", ok.ok(), ok.message());
        ck("...and is really there", Files.isDirectory(box.resolve("mods/packs")), "not on disk");

        var again = com.schecks.almin.WebFiles.mkdir(box, roots, datapacks, "mods", "packs");
        ck("making it twice is refused", !again.ok(), "it overwrote");

        var no = com.schecks.almin.WebFiles.mkdir(box, roots, datapacks, "logs", "x");
        ck("a read-only root is refused", !no.ok(), "it wrote into logs");
        ck("...and says where writes are allowed",
            no.message().contains("mods") && no.message().contains("config"), no.message());
        ck("...and made nothing", !Files.exists(box.resolve("logs/x")), "it created the folder");

        var dp = com.schecks.almin.WebFiles.mkdir(box, roots, datapacks, "world/datapacks", "mine");
        ck("a world's datapacks folder is writable", dp.ok(), dp.message());

        var escape = com.schecks.almin.WebFiles.mkdir(box, roots, datapacks, "../..", "evil");
        ck("a parent reference in the path escapes nothing", !escape.ok(), "it walked out");
        ck("...and wrote nothing outside",
            !Files.exists(box.getParent().resolve("evil")), "something appeared outside");

        for (String bad : new String[]{"..", "a/b", "a\\b", "", "   ", null}) {
            var r = com.schecks.almin.WebFiles.mkdir(box, roots, datapacks, "mods", bad);
            ck("the name " + (bad == null ? "null" : "'" + bad + "'") + " is refused",
                !r.ok(), r.message());
        }

        // A name that is fine but lands somewhere it should not.
        var root = com.schecks.almin.WebFiles.mkdir(box, roots, datapacks, "", "newtop");
        ck("a new folder at the server root is refused", !root.ok(), "it wrote at the root");
    }

    static void listings() throws Exception {
        var mods = send("GET", "/api/mods", null, cookie);
        ck("the mod list answers", mods.statusCode() == 200, mods.body());
        for (String field : new String[]{"\"unusedFiles\"", "\"advertise\"", "\"maxOffers\""}) {
            ck("mods carries " + field, mods.body().contains(field), mods.body());
        }
    }

    static String URLEncoder(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
    static HttpResponse<String> send(String method, String path, String body, String ck)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(java.time.Duration.ofSeconds(30));
        if (ck != null) b.header("Cookie", ck);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", "application/json")
              .method(method, HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
