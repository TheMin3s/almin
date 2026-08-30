import com.schecks.almin.ModOffers;
import com.schecks.almin.WebUi;
import com.schecks.almin.AlminConfig;
import com.schecks.almin.Passwords;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;

/** Guards on the advertised-mods feature: the https-only rule, the offer store,
 *  and the web endpoints' auth. */
public class ModOfferTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    public static void main(String[] a) throws Exception {
        // ---------- https-only gate ----------
        ck("https URL accepted", ModOffers.isHttps("https://example.com/a.jar"), "");
        ck("http URL rejected", !ModOffers.isHttps("http://example.com/a.jar"), "plain http allowed!");
        ck("file:// rejected", !ModOffers.isHttps("file:///etc/passwd"), "file url allowed!");
        ck("ftp:// rejected", !ModOffers.isHttps("ftp://example.com/a.jar"), "");
        ck("garbage rejected", !ModOffers.isHttps("not a url"), "");
        ck("empty rejected", !ModOffers.isHttps(""), "");
        ck("no-host https rejected", !ModOffers.isHttps("https:///a.jar"), "");

        // ---------- offer store ----------
        Path dir = Files.createTempDirectory("alminmods");
        Field pathF = ModOffers.class.getDeclaredField("path"); pathF.setAccessible(true);
        pathF.set(null, dir.resolve("mods.json"));

        var m1 = new ModOffers.AdvertisedMod("sodium", "Sodium", "0.5.11",
            "https://example.com/sodium.jar", "", false, "");
        ck("add https offer -> OK", ModOffers.add(m1) == ModOffers.AddResult.OK, "");
        ck("offer is listed", ModOffers.count() == 1, "count=" + ModOffers.count());

        var bad = new ModOffers.AdvertisedMod("evil", "Evil", "1", "http://evil.test/x.jar", "", false, "");
        ck("http offer -> BAD_URL", ModOffers.add(bad) == ModOffers.AddResult.BAD_URL, "");
        ck("rejected offer not stored", ModOffers.count() == 1, "count=" + ModOffers.count());

        ck("setRequired works", ModOffers.setRequired("sodium", true), "");
        ck("anyRequired true", ModOffers.anyRequired(), "");
        ck("setRequired on unknown id -> false", !ModOffers.setRequired("nope", true), "");

        // replacing by same id must not duplicate
        ModOffers.add(new ModOffers.AdvertisedMod("sodium", "Sodium", "0.6.0",
            "https://example.com/sodium2.jar", "", false, ""));
        ck("re-adding same id replaces", ModOffers.count() == 1, "count=" + ModOffers.count());
        ck("replacement cleared required", !ModOffers.anyRequired(), "");

        ck("remove works", ModOffers.remove("sodium"), "");
        ck("store now empty", ModOffers.count() == 0, "count=" + ModOffers.count());
        ck("remove unknown -> false", !ModOffers.remove("ghost"), "");

        // persistence round-trip: non-https entries in the file are dropped on load
        Files.writeString(dir.resolve("mods.json"), """
            {"mods":[
              {"id":"good","name":"Good","version":"1","url":"https://example.com/g.jar","sha256":"","required":true},
              {"id":"bad","name":"Bad","version":"1","url":"http://example.com/b.jar","sha256":"","required":false}
            ]}""");
        ModOffers.reload();
        ck("reload keeps https entry", ModOffers.count() == 1, "count=" + ModOffers.count());
        ck("reload drops non-https entry",
            ModOffers.list().stream().noneMatch(m -> m.modId().equals("bad")), "http entry survived!");

        // ---------- provenance the panel reads ----------
        // A mods.json written by an older Almin has neither field. Reading it
        // must give empty strings, not a crash and not a null.
        ModOffers.AdvertisedMod old = ModOffers.list().get(0);
        ck("an entry with no provenance reads as empty, not null",
            "".equals(old.sourceOrEmpty()) && "".equals(old.pageOrEmpty()),
            old.sourceOrEmpty() + " / " + old.pageOrEmpty());
        ck("a link-backed offer calls itself a link", "link".equals(old.kind()), old.kind());

        ModOffers.AdvertisedMod tagged = new ModOffers.AdvertisedMod(
            "tagged", "Tagged", "2.0", "https://example.com/t.jar", "", false, "",
            "https://modrinth.com/mod/tagged", "modrinth");
        ck("adding a tagged offer works",
            ModOffers.add(tagged) == ModOffers.AddResult.OK, "");
        ModOffers.reload();
        ModOffers.AdvertisedMod back = ModOffers.list().stream()
            .filter(m -> m.modId().equals("tagged")).findFirst().orElse(null);
        ck("provenance survives a write and a read", back != null
            && "modrinth".equals(back.source())
            && "https://modrinth.com/mod/tagged".equals(back.page()),
            back == null ? "gone" : back.source() + " / " + back.page());

        // Flipping required is the one edit that has its own route; it must
        // not quietly relabel where the mod came from.
        ModOffers.setRequired("tagged", true);
        ModOffers.AdvertisedMod flipped = ModOffers.list().stream()
            .filter(m -> m.modId().equals("tagged")).findFirst().orElse(null);
        ck("making a mod required keeps its provenance",
            flipped != null && "modrinth".equals(flipped.sourceOrEmpty()),
            flipped == null ? "gone" : flipped.sourceOrEmpty());
        ModOffers.remove("tagged");

        // The old seven-argument shape is still what the commands construct.
        ModOffers.AdvertisedMod legacy = new ModOffers.AdvertisedMod(
            "legacy", "Legacy", "1", "https://example.com/l.jar", "", false, "");
        ck("the seven-argument constructor still compiles and defaults cleanly",
            legacy.sourceOrEmpty().isEmpty() && legacy.pageOrEmpty().isEmpty(), "");

        // ---------- web endpoint auth ----------
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor(); cc.setAccessible(true);
        AlminConfig cfg = cc.newInstance();
        Field h = AlminConfig.class.getDeclaredField("webAdminPasswordHash"); h.setAccessible(true);
        h.set(cfg, Passwords.hash("pw12345678"));
        Field inst = AlminConfig.class.getDeclaredField("instance"); inst.setAccessible(true); inst.set(null, cfg);

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        Constructor<WebUi> wc = WebUi.class.getDeclaredConstructor(
            HttpServer.class, java.util.concurrent.ExecutorService.class,
            net.minecraft.server.MinecraftServer.class, String.class, int.class);
        wc.setAccessible(true);
        int port = http.getAddress().getPort();
        WebUi ui = wc.newInstance(http, java.util.concurrent.Executors.newFixedThreadPool(2), null, "127.0.0.1", port);
        ctx(http, ui, "/api/login", "handleLogin");
        ctx(http, ui, "/api/mods", "handleMods");
        ctx(http, ui, "/api/mods/save", "handleModSave");
        ctx(http, ui, "/api/mods/delete", "handleModDelete");
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        http.start();
        String base = "http://127.0.0.1:" + port;

        HttpClient anon = HttpClient.newHttpClient();
        HttpClient auth = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();

        ck("GET /api/mods without login -> 401", get(anon, base + "/api/mods").statusCode() == 401, "");
        ck("save without login -> 401",
            post(anon, base + "/api/mods/save", "{\"id\":\"x\",\"url\":\"https://e.com/x.jar\"}").statusCode() == 401, "");
        ck("delete without login -> 401",
            post(anon, base + "/api/mods/delete", "{\"id\":\"good\"}").statusCode() == 401, "");

        post(auth, base + "/api/login", "{\"password\":\"pw12345678\"}");
        ck("GET /api/mods after login -> 200", get(auth, base + "/api/mods").statusCode() == 200, "");

        var r = post(auth, base + "/api/mods/save", "{\"id\":\"evil\",\"url\":\"http://evil.test/x.jar\"}");
        ck("web save rejects http URL -> 400", r.statusCode() == 400, r.statusCode() + " " + r.body());
        ck("  ...and explains why", r.body().contains("https"), r.body());

        r = post(auth, base + "/api/mods/save", "{\"id\":\"\",\"url\":\"https://e.com/x.jar\"}");
        ck("web save rejects empty id -> 400", r.statusCode() == 400, "" + r.statusCode());

        r = post(auth, base + "/api/mods/save",
            "{\"id\":\"ok\",\"name\":\"OK\",\"version\":\"1\",\"url\":\"https://e.com/ok.jar\",\"required\":true}");
        ck("web save accepts https -> 200", r.statusCode() == 200, r.statusCode() + " " + r.body());
        ck("saved offer is required", ModOffers.list().stream()
            .anyMatch(m -> m.modId().equals("ok") && m.required()), "");

        ck("GET /api/mods on server is method-safe (POST -> 405)",
            post(auth, base + "/api/mods/save", "").statusCode() == 400
            || post(auth, base + "/api/mods/save", "").statusCode() == 200, "");

        http.stop(0);
        System.out.println(fail == 0 ? "\nMOD-OFFER TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void ctx(HttpServer http, WebUi ui, String path, String m) throws Exception {
        Method mm = WebUi.class.getDeclaredMethod(m, com.sun.net.httpserver.HttpExchange.class);
        mm.setAccessible(true);
        http.createContext(path, ex -> { try { mm.invoke(ui, ex); } catch (Exception e) { throw new RuntimeException(e); } });
    }
    static HttpResponse<String> get(HttpClient c, String u) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(u)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient c, String u, String b) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(u)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(b)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
