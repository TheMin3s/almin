package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The Almin web panel: two tiers over one HTTP server.
 *
 * <h3>Tiers</h3>
 * <ul>
 *   <li><b>Public</b> — no login. A small, hand-picked set of basic metrics
 *       ({@code Dashboard.buildPublic}): versions, uptime, a player count, TPS.
 *       Nothing about who plays here, the console, files, or settings. Served
 *       only when {@code web-public-metrics} is on.</li>
 *   <li><b>Admin</b> — password login. The full dashboard, the live console,
 *       a command terminal (server commands run as console, same as
 *       {@code /almin op cmd}), and a filesystem browser/editor with the same
 *       write rules as {@code /almin op}. This is remote control of the server;
 *       it is exactly as powerful as being an op at the console.</li>
 * </ul>
 *
 * <h3>Where TLS lives</h3>
 * This server speaks plain HTTP and binds loopback by default, because it is
 * meant to sit behind the Caddy reverse proxy that terminates TLS for the
 * public domain (a {@code Caddyfile} is written next to the config on first
 * start). A request is treated as trusted only when its TCP peer is loopback —
 * i.e. it came from the local proxy or the local machine. A request arriving
 * directly from a remote address (someone who bound this to a public interface
 * and skipped the proxy) is refused the admin tier, so the password never
 * crosses the wire in clear text. The session cookie is marked {@code Secure}
 * whenever the proxy reports HTTPS.
 *
 * <h3>Threading</h3>
 * HTTP worker threads never touch live server state directly. Read snapshots
 * are rebuilt on the server thread every {@link #REFRESH_TICKS} ticks; one-shot
 * operations (a command, a file read/write) are handed to the server thread via
 * {@code server.submit} and waited on with a timeout. World access stays on the
 * thread that owns it, and a slow client can't stall a tick.
 */
public final class WebUi {
    private static final int REFRESH_TICKS = 40;      // ~2s
    private static final int CONSOLE_LINES = 400;
    private static final String SESSION_COOKIE = "almin_session";
    private static final int MAX_BODY = (int) WebFiles.MAX_WRITE_BYTES + 4096;
    private static final long SERVER_OP_TIMEOUT_MS = 5000;

    private static volatile WebUi instance;

    private final HttpServer http;
    private final MinecraftServer server;
    private final WebSessions sessions = new WebSessions();
    private final String bind;
    private final int port;

    private volatile String publicJson = "{\"rows\":[],\"generated\":0}";
    private volatile String fullJson = "{\"rows\":[],\"generated\":0}";
    private int tickCounter = 0;

    /**
     * Whether the Minecraft server is up. In supervisor mode the panel outlives
     * the server, so every route that reaches into the server has to check this
     * first — the {@code server} reference is still non-null but dead.
     */
    private volatile boolean serverRunning = true;

    private WebUi(HttpServer http, MinecraftServer server, String bind, int port) {
        this.http = http;
        this.server = server;
        this.bind = bind;
        this.port = port;
    }

    // ---------- lifecycle ----------

    /** Starts the panel if enabled. Never throws — a bind failure just logs. */
    public static synchronized void start(MinecraftServer server) {
        if (instance != null) return;
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.webUiEnabled) {
            AlminLog.info("[almin] web panel disabled by config");
            return;
        }
        String bind = (cfg.webUiBind == null || cfg.webUiBind.isBlank()) ? "127.0.0.1" : cfg.webUiBind.trim();
        int port = cfg.webUiPort;
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 32);
            WebUi ui = new WebUi(http, server, bind, port);
            http.createContext("/", ui::handleRoot);
            http.createContext("/api/session", ui::handleSession);
            http.createContext("/api/public", ui::handlePublic);
            http.createContext("/api/login", ui::handleLogin);
            http.createContext("/api/logout", ui::handleLogout);
            http.createContext("/api/state", ui::handleState);
            http.createContext("/api/console", ui::handleConsole);
            http.createContext("/api/exec", ui::handleExec);
            http.createContext("/api/files", ui::handleFiles);
            http.createContext("/api/file", ui::handleFile);
            http.createContext("/api/file/delete", ui::handleFileDelete);
            http.createContext("/api/file/rename", ui::handleFileRename);
            http.createContext("/api/server", ui::handleServerControl);
            http.createContext("/api/mods", ui::handleMods);
            http.createContext("/api/mods/save", ui::handleModSave);
            http.createContext("/api/mods/delete", ui::handleModDelete);
            http.createContext("/api/mods/files", ui::handleModFiles);
            http.createContext("/api/mods/upload", ui::handleModUpload);
            http.createContext("/api/mods/files/delete", ui::handleModFileDelete);
            // In supervisor mode the web threads must be non-daemon, or the JVM
            // exits the moment the server thread ends and takes the panel with it.
            http.setExecutor(Executors.newFixedThreadPool(4, threadFactory(cfg.webSupervisor)));
            http.start();
            instance = ui;
            ServerTickEvents.END_SERVER_TICK.register(ui::onTick);
            ui.rebuild();
            ui.writeCaddyfile(cfg);
            boolean pw = cfg.webAdminPasswordHash != null && !cfg.webAdminPasswordHash.isBlank();
            AlminLog.info("[almin] web panel on http://{}:{} (public metrics {}, admin login {})",
                bind, port, cfg.webPublicMetrics ? "on" : "off",
                pw ? "ready" : "NO PASSWORD SET — run /almin op web password <pw>");
        } catch (IOException e) {
            AlminLog.warn("[almin] web panel could not bind {}:{} — {}", bind, port, e.getMessage());
        } catch (RuntimeException e) {
            AlminLog.warn("[almin] web panel failed to start: {}", e.toString());
        }
    }

    public static synchronized void stop() {
        WebUi ui = instance;
        if (ui == null) return;
        instance = null;
        try {
            ui.http.stop(0);
        } catch (RuntimeException ignored) {
            // shutting down anyway
        }
    }

    public static boolean running()  { return instance != null; }
    public static int port()         { WebUi u = instance; return u == null ? -1 : u.port; }
    public static String bind()      { WebUi u = instance; return u == null ? "" : u.bind; }

    /** Drops every live web session — called when the password changes. */
    public static void invalidateSessions() {
        WebUi u = instance;
        if (u != null) u.sessions.closeAll();
    }

    private static ThreadFactory threadFactory(boolean supervisor) {
        return r -> {
            Thread t = new Thread(r, "Almin-web");
            // Daemon threads never hold the JVM open; supervisor mode needs the
            // opposite, so the panel survives the server thread ending.
            t.setDaemon(!supervisor);
            return t;
        };
    }

    /**
     * Called when the Minecraft server has stopped. In supervisor mode the panel
     * stays up (showing the server as stopped, offering Start); otherwise it
     * shuts down with everything else.
     */
    public static void onServerStopped() {
        WebUi ui = instance;
        if (ui == null) return;
        ui.serverRunning = false;
        if (!AlminConfig.get().webSupervisor) {
            stop();
            return;
        }
        ui.publicJson = stoppedJson();
        ui.fullJson = stoppedJson();
        AlminLog.info("[almin] server stopped — web panel still up (supervisor mode)");
    }

    private static String stoppedJson() {
        JsonObject root = new JsonObject();
        root.add("rows", new JsonArray());
        root.addProperty("generated", System.currentTimeMillis());
        root.addProperty("serverRunning", false);
        return root.toString();
    }

    // ---------- snapshots ----------

    private void onTick(MinecraftServer s) {
        if (++tickCounter < REFRESH_TICKS) return;
        tickCounter = 0;
        rebuild();
    }

    /** Runs on the server thread: renders both tiers to JSON. */
    private void rebuild() {
        if (!serverRunning) return;
        try {
            Dashboard.Metrics m = Dashboard.metrics(server);
            publicJson = rowsJson(Dashboard.buildPublic(server), m);
            fullJson = rowsJson(Dashboard.build(server, null).rows(), m);
        } catch (RuntimeException e) {
            AlminLog.warn("[almin] web snapshot failed: {}", e.toString());
        }
    }

    /**
     * Serialises the formatted rows plus the raw numbers behind them. The rows
     * drive the detail lists; the raw numbers drive the stat tiles and meters,
     * which need magnitudes rather than pre-formatted strings.
     */
    private String rowsJson(List<DashboardPayload.Row> rows, Dashboard.Metrics m) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (DashboardPayload.Row r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", r.kind());
            o.addProperty("label", r.label());
            o.addProperty("value", r.value());
            o.addProperty("accent", r.accent() == 0 ? "" : String.format("#%06x", r.accent() & 0xFFFFFF));
            arr.add(o);
        }
        root.add("rows", arr);

        JsonObject t = new JsonObject();
        t.addProperty("tps", Math.round(m.tps() * 100) / 100.0);
        t.addProperty("tpsTarget", m.tpsTarget());
        t.addProperty("mspt", Math.round(m.mspt() * 100) / 100.0);
        t.addProperty("players", m.players());
        t.addProperty("maxPlayers", m.maxPlayers());
        t.addProperty("memUsed", Dashboard.bytes(m.memUsed()));
        t.addProperty("memMax", Dashboard.bytes(m.memMax()));
        t.addProperty("memPct", m.memPct());
        t.addProperty("uptime", m.uptimeMillis() == 0L ? "—" : Dashboard.duration(m.uptimeMillis()));
        t.addProperty("chunks", m.chunks());
        t.addProperty("entities", m.entities());
        root.add("metrics", t);

        root.addProperty("serverRunning", serverRunning);
        root.addProperty("generated", System.currentTimeMillis());
        return root.toString();
    }

    // ---------- routes: read ----------

    private void handleRoot(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "Method not allowed"); return; }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/favicon.ico")) { ex.sendResponseHeaders(204, -1); return; }
            if (!path.equals("/")) { send(ex, 404, "text/plain", "Not found"); return; }
            send(ex, 200, "text/html; charset=utf-8", PAGE_HTML);
        } finally {
            ex.close();
        }
    }

    private void handleSession(HttpExchange ex) throws IOException {
        try {
            AlminConfig cfg = AlminConfig.get();
            boolean pwSet = cfg.webAdminPasswordHash != null && !cfg.webAdminPasswordHash.isBlank();
            JsonObject o = new JsonObject();
            o.addProperty("authed", authed(ex));
            o.addProperty("secure", secure(ex));
            o.addProperty("passwordSet", pwSet);
            o.addProperty("publicMetrics", cfg.webPublicMetrics);
            o.addProperty("serverRunning", serverRunning);
            o.addProperty("supervisor", cfg.webSupervisor);
            o.addProperty("canStart", cfg.webSupervisor
                && cfg.webStartCommand != null && !cfg.webStartCommand.isBlank());
            json(ex, 200, o.toString());
        } finally {
            ex.close();
        }
    }

    private void handlePublic(HttpExchange ex) throws IOException {
        try {
            if (!AlminConfig.get().webPublicMetrics) { json(ex, 403, "{\"disabled\":true}"); return; }
            json(ex, 200, publicJson);
        } finally {
            ex.close();
        }
    }

    private void handleState(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            json(ex, 200, fullJson);
        } finally {
            ex.close();
        }
    }

    private void handleConsole(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            JsonObject o = new JsonObject();
            JsonArray lines = new JsonArray();
            ConsoleTap tap = ConsoleTap.get();
            if (tap != null) for (String l : tap.recentLines(CONSOLE_LINES)) lines.add(l);
            o.add("lines", lines);
            json(ex, 200, o.toString());
        } finally {
            ex.close();
        }
    }

    // ---------- routes: auth ----------

    private void handleLogin(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            AlminConfig cfg = AlminConfig.get();
            if (cfg.webAdminPasswordHash == null || cfg.webAdminPasswordHash.isBlank()) {
                json(ex, 403, "{\"error\":\"no-password\"}"); return;
            }
            if (!secure(ex)) { json(ex, 403, "{\"error\":\"insecure\"}"); return; }
            String key = clientKey(ex);
            if (sessions.lockedOut(key)) {
                AlminLog.warn("[almin] web login locked out for {}", key);
                json(ex, 429, "{\"error\":\"locked\",\"minutes\":" + sessions.lockoutMinutes() + "}"); return;
            }
            JsonObject body = readBody(ex);
            String pw = body.has("password") ? body.get("password").getAsString() : "";
            if (Passwords.verify(pw, cfg.webAdminPasswordHash)) {
                sessions.recordSuccess(key);
                String id = sessions.open(cfg.webSessionMinutes);
                setSessionCookie(ex, id, behindTls(ex));
                AlminLog.info("[almin] web login succeeded for {}", key);
                json(ex, 200, "{\"ok\":true}");
            } else {
                int remaining = sessions.recordFailure(key);
                AlminLog.warn("[almin] web login FAILED for {} ({} attempt(s) left)", key, remaining);
                json(ex, 401, "{\"ok\":false,\"remaining\":" + remaining + "}");
            }
        } finally {
            ex.close();
        }
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        try {
            sessions.close(cookie(ex, SESSION_COOKIE));
            clearSessionCookie(ex);
            json(ex, 200, "{\"ok\":true}");
        } finally {
            ex.close();
        }
    }

    // ---------- routes: terminal ----------

    private void handleExec(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String raw = body.has("command") ? body.get("command").getAsString().trim() : "";
            if (raw.isEmpty()) { json(ex, 400, "{\"error\":\"empty\"}"); return; }
            String command = raw.startsWith("/") ? raw.substring(1) : raw;
            AlminLog.info("[almin] web terminal ran: /{}", command);
            Boolean ok = onServer(() -> {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "/" + command);
                return Boolean.TRUE;
            }, Boolean.FALSE);
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("ran", command);
            json(ex, 200, o.toString());
        } finally {
            ex.close();
        }
    }

    // ---------- routes: server control ----------

    /**
     * Stops or starts the Minecraft server.
     *
     * <p>Stop always works: it is the same graceful halt as {@code /stop}.
     * What happens next depends on {@code web-supervisor} — with it off the JVM
     * exits (and whatever wrapper you run restarts it, as today); with it on the
     * panel stays up and offers Start.
     *
     * <p>Start cannot boot a server inside this JVM — Minecraft's bootstrap is
     * one-shot — so it launches the configured command as a fresh process and
     * hands the port over by shutting this one down.
     */
    private void handleServerControl(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String action = body.has("action") ? body.get("action").getAsString() : "";
            AlminConfig cfg = AlminConfig.get();

            if ("stop".equals(action)) {
                if (!serverRunning) { json(ex, 409, err("The server is already stopped.")); return; }
                AlminLog.warn("[almin] web panel requested server STOP");
                json(ex, 200, "{\"ok\":true,\"action\":\"stop\"}");
                // Halt on the server thread, after the response is on its way.
                server.execute(() -> server.halt(false));
                return;
            }

            if ("start".equals(action)) {
                if (!cfg.webSupervisor) {
                    json(ex, 409, err("Start needs supervisor mode — set web-supervisor true.")); return;
                }
                if (serverRunning) { json(ex, 409, err("The server is already running.")); return; }
                String cmd = cfg.webStartCommand == null ? "" : cfg.webStartCommand.trim();
                if (cmd.isEmpty()) {
                    json(ex, 409, err("No start command configured — set web-start-command.")); return;
                }
                AlminLog.warn("[almin] web panel requested server START via: {}", cmd);
                json(ex, 200, "{\"ok\":true,\"action\":\"start\"}");
                handOffTo(cmd);
                return;
            }

            json(ex, 400, err("Unknown action: " + action));
        } finally {
            ex.close();
        }
    }

    /**
     * Launches {@code cmd} as a detached process and then shuts this JVM down,
     * so the new server can take over the web port. Runs off the HTTP thread so
     * the response is already flushed.
     */
    private void handOffTo(String cmd) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(400);                     // let the response reach the browser
                Path dir = server.getServerDirectory().toAbsolutePath().normalize();
                ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
                pb.directory(dir.toFile());
                pb.inheritIO();
                pb.start();
                AlminLog.info("[almin] start command launched; handing over and exiting");
                AlminLog.close();
                try {
                    http.stop(0);                      // release the port for the new process
                } catch (RuntimeException ignored) {
                    // exiting anyway
                }
                Runtime.getRuntime().halt(0);
            } catch (Exception e) {
                AlminLog.warn("[almin] start command failed: {}", e.toString());
            }
        }, "Almin-web-handoff");
        t.setDaemon(false);
        t.start();
    }

    // ---------- routes: files ----------

    private void handleFiles(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!requireServer(ex)) return;
            String rel = queryParam(ex, "path");
            WebFiles.Listing listing = onServer(() -> {
                try { return WebFiles.list(server, rel); }
                catch (IOException e) { return new WebFiles.Listing(rel, false, -1,
                    List.of(new WebFiles.Entry("!error:" + e.getMessage(), false, -1))); }
            }, null);
            if (listing == null) { json(ex, 500, "{\"error\":\"timeout\"}"); return; }
            JsonObject o = new JsonObject();
            o.addProperty("path", listing.path());
            o.addProperty("isDir", listing.isDir());
            o.addProperty("fileSize", listing.fileSize());
            JsonArray arr = new JsonArray();
            for (WebFiles.Entry e : listing.entries()) {
                JsonObject je = new JsonObject();
                je.addProperty("name", e.name());
                je.addProperty("directory", e.directory());
                je.addProperty("size", e.size());
                arr.add(je);
            }
            o.add("entries", arr);
            json(ex, 200, o.toString());
        } finally {
            ex.close();
        }
    }

    private void handleFile(HttpExchange ex) throws IOException {
        try {
            if ("GET".equals(ex.getRequestMethod())) {
                if (!requireAuth(ex)) return;
                if (!requireServer(ex)) return;
                String rel = queryParam(ex, "path");
                String[] out = onServer(() -> {
                    try { return new String[]{ WebFiles.read(server, rel), null }; }
                    catch (IOException e) { return new String[]{ null, e.getMessage() }; }
                }, new String[]{ null, "timeout" });
                if (out[1] != null) { json(ex, 400, err(out[1])); return; }
                JsonObject o = new JsonObject();
                o.addProperty("path", rel);
                o.addProperty("content", out[0]);
                json(ex, 200, o.toString());
            } else if ("POST".equals(ex.getRequestMethod())) {
                if (!requireAuthSecure(ex)) return;
                if (!requireServer(ex)) return;
                JsonObject body = readBody(ex);
                String rel = body.has("path") ? body.get("path").getAsString() : "";
                String content = body.has("content") ? body.get("content").getAsString() : "";
                WebFiles.Result r = onServer(() -> WebFiles.write(server, rel, content),
                    WebFiles.Result.fail("timeout"));
                AlminLog.info("[almin] web wrote {} ({})", rel, r.ok() ? "ok" : r.message());
                json(ex, r.ok() ? 200 : 400, result(r));
            } else {
                json(ex, 405, "{\"error\":\"method\"}");
            }
        } finally {
            ex.close();
        }
    }

    private void handleFileDelete(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String rel = body.has("path") ? body.get("path").getAsString() : "";
            WebFiles.Result r = onServer(() -> WebFiles.delete(server, rel), WebFiles.Result.fail("timeout"));
            AlminLog.info("[almin] web deleted {} ({})", rel, r.ok() ? "ok" : r.message());
            json(ex, r.ok() ? 200 : 400, result(r));
        } finally {
            ex.close();
        }
    }

    private void handleFileRename(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String rel = body.has("path") ? body.get("path").getAsString() : "";
            String name = body.has("name") ? body.get("name").getAsString() : "";
            WebFiles.Result r = onServer(() -> WebFiles.rename(server, rel, name), WebFiles.Result.fail("timeout"));
            AlminLog.info("[almin] web renamed {} -> {} ({})", rel, name, r.ok() ? "ok" : r.message());
            json(ex, r.ok() ? 200 : 400, result(r));
        } finally {
            ex.close();
        }
    }

    // ---------- routes: advertised mods ----------

    /** The current offer list plus the three settings that govern it. */
    private void handleMods(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            AlminConfig cfg = AlminConfig.get();
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            for (ModOffers.AdvertisedMod m : ModOffers.list()) {
                JsonObject j = new JsonObject();
                j.addProperty("id", m.modId());
                j.addProperty("name", m.name());
                j.addProperty("version", m.version());
                j.addProperty("url", m.url());
                j.addProperty("sha256", m.sha256());
                j.addProperty("required", m.required());
                j.addProperty("file", m.file() == null ? "" : m.file());
                arr.add(j);
            }
            o.add("mods", arr);
            o.addProperty("advertise", cfg.modsAdvertise);
            o.addProperty("denyKicks", cfg.modsDenyKicks);
            o.addProperty("requireClientMod", cfg.requireClientMod);
            json(ex, 200, o.toString());
        } finally {
            ex.close();
        }
    }

    /** Adds or replaces one offer. The https-only rule is enforced in ModOffers. */
    private void handleModSave(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject b = readBody(ex);
            String id = b.has("id") ? b.get("id").getAsString().trim() : "";
            String url = b.has("url") ? b.get("url").getAsString().trim() : "";
            String file = b.has("file") ? b.get("file").getAsString().trim() : "";
            if (id.isEmpty() || (url.isEmpty() && file.isEmpty())) {
                json(ex, 400, err("A mod id and either a server file or an https URL are required."));
                return;
            }
            ModOffers.AdvertisedMod mod = new ModOffers.AdvertisedMod(
                id,
                b.has("name") && !b.get("name").getAsString().isBlank() ? b.get("name").getAsString().trim() : id,
                b.has("version") ? b.get("version").getAsString().trim() : "",
                url,
                b.has("sha256") ? b.get("sha256").getAsString().trim() : "",
                b.has("required") && b.get("required").getAsBoolean(),
                b.has("file") ? b.get("file").getAsString().trim() : "");
            ModOffers.AddResult r = ModOffers.add(mod);
            AlminLog.info("[almin] web set mod offer {} -> {} ({})", id, url, r);
            switch (r) {
                case OK -> json(ex, 200, "{\"ok\":true}");
                case BAD_URL -> json(ex, 400, err("The URL must be https:// — clients refuse anything else."));
                case BAD_FILE -> json(ex, 400, err("Invalid filename — use a plain .jar name from modfiles/."));
                case MISSING_FILE -> json(ex, 400, err("That file isn't in config/almin/modfiles/."));
                case FULL -> json(ex, 400, err("Already advertising the maximum of " + ModOffers.MAX_OFFERS + "."));
                case NOT_LOADED -> json(ex, 409, err("Mod offers aren't loaded yet."));
                default -> json(ex, 500, err("Saved in memory but mods.json couldn't be written."));
            }
        } finally {
            ex.close();
        }
    }

    private void handleModDelete(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject b = readBody(ex);
            String id = b.has("id") ? b.get("id").getAsString().trim() : "";
            if (!ModOffers.remove(id)) { json(ex, 404, err("Not advertised: " + id)); return; }
            AlminLog.info("[almin] web removed mod offer {}", id);
            json(ex, 200, "{\"ok\":true}");
        } finally {
            ex.close();
        }
    }

    /** Jars sitting in modfiles/, ready to be advertised. */
    private void handleModFiles(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!requireServer(ex)) return;
            List<String> files = onServer(ModOffers::availableFiles, List.of());
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String f : files) arr.add(f);
            o.add("files", arr);
            o.addProperty("maxBytes", ModOffers.MAX_FILE_BYTES);
            json(ex, 200, o.toString());
        } finally {
            ex.close();
        }
    }

    /**
     * Uploads a jar into modfiles/ as a raw request body.
     *
     * <p>The filename comes from the {@code name} query parameter and is
     * resolved by {@link ModOffers#resolveModFile}, which only accepts a bare
     * {@code .jar} name inside that folder — a path can't be smuggled in. The
     * body is capped, and the finished file must actually be a Fabric mod jar
     * before it is kept, so this endpoint can't be used to drop arbitrary
     * content onto the server.
     */
    private void handleModUpload(HttpExchange ex) throws IOException {
        Path tmp = null;
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }

            String name = queryParam(ex, "name");
            Path target = onServer(() -> ModOffers.resolveModFile(name), null);
            if (target == null) {
                json(ex, 400, err("Filename must be a plain .jar name, e.g. sodium-0.5.11.jar"));
                return;
            }
            Path dir = ModOffers.modFilesDir();
            if (dir == null) { json(ex, 409, err("Mod file storage isn't ready yet.")); return; }

            long max = ModOffers.MAX_FILE_BYTES;
            tmp = Files.createTempFile(dir, ".almin-upload-", ".part");
            long written;
            try (var in = ex.getRequestBody(); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > max) {
                        json(ex, 413, err("File exceeds the " + (max / (1024 * 1024)) + " MB limit."));
                        return;
                    }
                    out.write(buf, 0, n);
                }
                written = total;
            }
            if (written == 0) { json(ex, 400, err("Empty upload.")); return; }
            if (!UpdateChecker.looksLikeValidMod(tmp)) {
                json(ex, 400, err("That file isn't a Fabric mod jar (no fabric.mod.json inside)."));
                return;
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            AlminLog.info("[almin] web uploaded mod file {} ({} bytes)", name, written);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("name", name);
            o.addProperty("bytes", written);
            json(ex, 200, o.toString());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
            ex.close();
        }
    }

    /** Removes a jar from modfiles/. Offers pointing at it stop being sent. */
    private void handleModFileDelete(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject b = readBody(ex);
            String name = b.has("name") ? b.get("name").getAsString() : "";
            Path target = ModOffers.resolveModFile(name);
            if (target == null || !Files.isRegularFile(target)) { json(ex, 404, err("No such file.")); return; }
            Files.delete(target);
            AlminLog.info("[almin] web deleted mod file {}", name);
            json(ex, 200, "{\"ok\":true}");
        } catch (IOException e) {
            json(ex, 500, err("Delete failed: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    // ---------- gates ----------

    private boolean authed(HttpExchange ex) {
        return sessions.valid(cookie(ex, SESSION_COOKIE));
    }

    /** 401s and returns false when the request has no valid session. */
    private boolean requireAuth(HttpExchange ex) throws IOException {
        if (authed(ex)) return true;
        json(ex, 401, "{\"error\":\"unauthorised\"}");
        return false;
    }

    /** As {@link #requireAuth} but also demands a loopback (proxied/local) peer. */
    private boolean requireAuthSecure(HttpExchange ex) throws IOException {
        if (!authed(ex)) { json(ex, 401, "{\"error\":\"unauthorised\"}"); return false; }
        if (!secure(ex)) { json(ex, 403, "{\"error\":\"insecure\"}"); return false; }
        return true;
    }

    /**
     * Refuses routes that reach into the Minecraft server while it is stopped.
     * Without this they would block on {@code server.submit} until the timeout,
     * tying up a web thread for nothing.
     */
    private boolean requireServer(HttpExchange ex) throws IOException {
        if (serverRunning) return true;
        json(ex, 409, err("The Minecraft server is stopped."));
        return false;
    }

    /**
     * Trusted iff the TCP peer is loopback: the request came through the local
     * proxy or from the local machine. A direct connection from a remote address
     * (proxy bypassed) is never trusted, so no forged header can promote it.
     */
    private static boolean secure(HttpExchange ex) {
        InetSocketAddress remote = ex.getRemoteAddress();
        InetAddress a = remote == null ? null : remote.getAddress();
        return a != null && a.isLoopbackAddress();
    }

    /** Whether the external leg is HTTPS, per the proxy — only trusted from loopback. */
    private static boolean behindTls(HttpExchange ex) {
        return secure(ex) && "https".equalsIgnoreCase(firstHeader(ex, "X-Forwarded-Proto"));
    }

    /**
     * Rate-limit identity: the real client's address.
     *
     * <p>Behind our reverse proxy the trustworthy value is the <em>last</em>
     * {@code X-Forwarded-For} hop — the address the proxy itself saw and
     * appended. Earlier hops are copied verbatim from whatever the client sent,
     * so keying the login lockout on the first hop would let an attacker evade
     * it by rotating a forged header on every guess. We trust exactly one proxy,
     * on loopback, so we read the last hop and only when the peer is loopback;
     * otherwise we fall back to the raw TCP peer.
     */
    private static String clientKey(HttpExchange ex) {
        String xff = firstHeader(ex, "X-Forwarded-For");
        if (secure(ex) && xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            String lastHop = hops[hops.length - 1].trim();
            if (!lastHop.isEmpty()) return lastHop;
        }
        InetSocketAddress remote = ex.getRemoteAddress();
        InetAddress a = remote == null ? null : remote.getAddress();
        return a == null ? "?" : a.getHostAddress();
    }

    // ---------- server-thread bridge ----------

    private <T> T onServer(Supplier<T> job, T fallback) {
        try {
            return server.submit(job).get(SERVER_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            AlminLog.warn("[almin] web server-op failed: {}", e.toString());
            return fallback;
        }
    }

    // ---------- Caddy config ----------

    /**
     * Writes a ready-to-edit Caddyfile next to the config, once. It is scoped to
     * this instance and deliberately avoids ports 80 and 443: the panel is
     * published on its own HTTPS port and certificates come from the DNS-01
     * challenge, which needs no inbound port — so Caddy won't collide with any
     * other web service on the host.
     */
    private void writeCaddyfile(AlminConfig cfg) {
        try {
            Path dir = server.getServerDirectory().resolve("config").resolve("almin");
            Path file = dir.resolve("Caddyfile");
            if (Files.exists(file)) return;
            Files.createDirectories(dir);
            String data = dir.resolve("caddy-data").toAbsolutePath().toString();
            String text = """
                # Almin web panel — TLS reverse proxy, scoped to this Minecraft instance.
                #
                # Written once; edit freely. Designed NOT to touch port 80 or 443 so it
                # never collides with anything else on the host:
                #   * the panel is published on its own HTTPS port (change 8443 below),
                #   * certificates come from the DNS-01 challenge (no inbound port needed),
                #     so nothing listens on port 80.
                #
                # Run Caddy scoped to this folder so it writes nothing system-wide:
                #   caddy run --config "%CADDYFILE%"
                #
                {
                	# No automatic HTTP->HTTPS redirect vhost, so nothing binds port 80.
                	auto_https disable_redirects
                	# Keep all Caddy state inside this instance.
                	storage file_system "%DATA%"
                }

                # Public HTTPS port for the panel. Must NOT be 80 or 443 if those are
                # used by other services on this machine.
                alexmiod.com:8443 {
                	# DNS-01 issuance needs no inbound port. Replace with your DNS
                	# provider's Caddy module and API token (see the Almin README),
                	# e.g.  tls { dns cloudflare {env.CF_API_TOKEN} }
                	tls {
                		# dns <provider> <token>
                	}
                	# Forward only to the loopback panel; nothing else is exposed.
                	reverse_proxy 127.0.0.1:%PORT%
                }
                """
                .replace("%CADDYFILE%", file.toAbsolutePath().toString())
                .replace("%DATA%", data)
                .replace("%PORT%", String.valueOf(port));
            Files.writeString(file, text, StandardCharsets.UTF_8);
            AlminLog.info("[almin] wrote a starter Caddyfile to {}", file);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not write Caddyfile: {}", e.getMessage());
        }
    }

    // ---------- http plumbing ----------

    private JsonObject readBody(HttpExchange ex) throws IOException {
        byte[] data = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (data.length > MAX_BODY) throw new IOException("request body too large");
        if (data.length == 0) return new JsonObject();
        try {
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return "";
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String firstHeader(HttpExchange ex, String name) {
        List<String> v = ex.getRequestHeaders().get(name);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static String cookie(HttpExchange ex, String name) {
        Map<String, List<String>> headers = ex.getRequestHeaders();
        List<String> cookies = headers.get("Cookie");
        if (cookies == null) return null;
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String c = part.trim();
                if (c.startsWith(name + "=")) return c.substring(name.length() + 1);
            }
        }
        return null;
    }

    private void setSessionCookie(HttpExchange ex, String id, boolean secureFlag) {
        String c = SESSION_COOKIE + "=" + id + "; Path=/; HttpOnly; SameSite=Strict"
            + (secureFlag ? "; Secure" : "");
        ex.getResponseHeaders().add("Set-Cookie", c);
    }

    private void clearSessionCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie",
            SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    private static String err(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o.toString();
    }

    private static String result(WebFiles.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok());
        o.addProperty("message", r.message());
        return o.toString();
    }

    private void json(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", body);
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    // ---------- page ----------
    /**
     * The panel page.
     *
     * <p>The tiles read the raw {@code metrics} object; the section cards read
     * the pre-formatted {@code rows}. Status colours (TPS health, memory
     * pressure) always ship with a word next to them — "Healthy", "Strained",
     * "Critical" — so state is never carried by colour alone.
     */
    private static final String PAGE_HTML = """
        <!doctype html><meta charset="utf-8"><title>Almin</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          :root{
            --bg:#101216; --card:#181b21; --card2:#1e222a; --line:#2b3039;
            --ink:#e8eaed; --dim:#98a1ae; --mute:#6b7480; --brand:#ffab33;
            --good:#0ca30c; --warn:#fab219; --crit:#d03b3b; --track:#272c35;
          }
          *{box-sizing:border-box}
          body{background:var(--bg);color:var(--ink);margin:0;
               font:14px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;
               -webkit-font-smoothing:antialiased}
          .num{font-variant-numeric:tabular-nums}

          header{display:flex;align-items:center;gap:14px;padding:13px 22px;
                 background:linear-gradient(180deg,#181b21,#141720);
                 border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}
          .brand{font-size:15px;font-weight:650;letter-spacing:.4px;color:var(--brand)}
          .pill{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;
                font-size:12px;font-weight:600;border:1px solid var(--line);background:var(--card2);color:var(--dim)}
          .dot{width:7px;height:7px;border-radius:50%;background:var(--mute);flex:none}
          .pill.up .dot{background:var(--good);box-shadow:0 0 0 3px rgba(12,163,12,.18)}
          .pill.down .dot{background:var(--crit);box-shadow:0 0 0 3px rgba(208,59,59,.18)}
          .pill.up{color:#9ee39e}.pill.down{color:#f0a3a3}
          .pill span:last-child{white-space:nowrap}
          .spacer{margin-left:auto}
          .age{color:var(--mute);font-size:12px;white-space:nowrap}
          button{font:inherit;cursor:pointer}
          .btn{background:var(--card2);border:1px solid var(--line);color:var(--ink);
               padding:6px 13px;border-radius:7px;white-space:nowrap;
               transition:border-color .15s,background .15s}
          .btn:hover{border-color:var(--brand);background:#232833}
          .btn.danger:hover{border-color:var(--crit);color:#ffb3b3}
          .btn.go:hover{border-color:var(--good);color:#a8e6a8}
          .btn[disabled]{opacity:.4;cursor:not-allowed}
          .btn[disabled]:hover{border-color:var(--line);background:var(--card2);color:var(--ink)}

          nav{display:flex;gap:2px;padding:12px 22px 0;flex-wrap:wrap;border-bottom:1px solid var(--line)}
          nav button{background:none;border:0;border-bottom:2px solid transparent;color:var(--dim);
                     padding:8px 15px;border-radius:6px 6px 0 0;font-weight:500}
          nav button:hover{color:var(--ink)}
          nav button.on{color:var(--brand);border-bottom-color:var(--brand)}
          main{padding:20px 22px 34px;max-width:1500px;margin:0 auto}
          .panel{display:none}.panel.on{display:block}

          /* ---- KPI tiles ---- */
          .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:13px}
          .tile{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px 13px;
                position:relative;overflow:hidden}
          .tile::before{content:"";position:absolute;inset:0 0 auto 0;height:2px;background:var(--track)}
          .tile.good::before{background:var(--good)}
          .tile.warn::before{background:var(--warn)}
          .tile.crit::before{background:var(--crit)}
          .tile .cap{font-size:11px;text-transform:uppercase;letter-spacing:.9px;color:var(--mute);font-weight:600}
          .tile .big{font-size:29px;font-weight:640;line-height:1.15;margin-top:5px;letter-spacing:-.5px}
          .tile .sub{font-size:12px;color:var(--dim);margin-top:2px}
          .state{font-size:11px;font-weight:700;letter-spacing:.4px;text-transform:uppercase}
          .state.good{color:#57c957}.state.warn{color:var(--warn)}.state.crit{color:#e97070}
          .meter{height:6px;border-radius:99px;background:var(--track);margin-top:9px;overflow:hidden}
          .meter i{display:block;height:100%;border-radius:99px;background:var(--brand);
                   transition:width .45s cubic-bezier(.4,0,.2,1)}
          .meter i.good{background:var(--good)}.meter i.warn{background:var(--warn)}.meter i.crit{background:var(--crit)}
          .spark{margin-top:8px;display:block;width:100%;height:32px;overflow:visible}
          .sparkwrap{position:relative}
          .sparktip{position:absolute;pointer-events:none;background:#0b0d11;border:1px solid var(--line);
                    border-radius:6px;padding:2px 7px;font-size:11px;color:var(--ink);white-space:nowrap;
                    transform:translate(-50%,-120%);opacity:0;transition:opacity .12s}

          /* ---- section cards ---- */
          .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:13px;margin-top:16px}
          section{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
          h2{margin:0 0 11px;font-size:11px;text-transform:uppercase;letter-spacing:.9px;color:var(--brand);font-weight:700}
          .row{display:flex;gap:12px;padding:5px 0;border-bottom:1px solid rgba(255,255,255,.045)}
          .row:last-child{border-bottom:0}
          .k{color:var(--dim);white-space:nowrap}
          .v{margin-left:auto;text-align:right;font-variant-numeric:tabular-nums}
          .note{color:var(--mute);font-style:italic;padding:5px 0;font-size:13px}

          pre{background:#0b0d11;border:1px solid var(--line);border-radius:12px;margin-top:14px;
              padding:13px 15px;max-height:62vh;overflow:auto;white-space:pre-wrap;word-break:break-word;
              font:12px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace;color:#c9d1d9}
          .warnline{color:var(--warn)}.errline{color:#ff7a6b}

          input,textarea{background:#0b0d11;border:1px solid var(--line);color:var(--ink);border-radius:8px;
                         padding:9px 11px;font:inherit;width:100%;outline:none}
          input:focus,textarea:focus{border-color:var(--brand)}
          textarea{font:12px/1.5 ui-monospace,Menlo,monospace;min-height:50vh;resize:vertical}
          .term{display:flex;gap:8px;margin-top:12px}
          .term input{font:12px/1.5 ui-monospace,Menlo,monospace}
          .files{display:grid;grid-template-columns:minmax(250px,1fr) 2fr;gap:14px;margin-top:14px}
          .flist{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:8px;
                 max-height:64vh;overflow:auto}
          .fentry{padding:6px 9px;border-radius:7px;cursor:pointer;display:flex;gap:8px;align-items:center}
          .fentry:hover{background:var(--card2)}
          .fentry .sz{margin-left:auto;color:var(--mute);font-size:11px}
          .dir{color:var(--brand)}
          .editor{display:flex;flex-direction:column;gap:9px}
          .editor .bar{display:flex;gap:8px;align-items:center}
          .editor .bar input{flex:1}
          .msg{min-height:18px;font-size:12px;color:var(--dim)}
          .msg.err{color:#e97070}.msg.ok{color:#57c957}
          .login{max-width:380px;margin:52px auto;background:var(--card);border:1px solid var(--line);
                 border-radius:12px;padding:26px}
          .login h2{margin-top:0}
          .login .btn{width:100%;margin-top:11px;padding:10px}
          .muted{color:var(--dim);font-size:12px}
          code{background:#0b0d11;padding:1px 5px;border-radius:4px;font-size:12px}
          .banner{display:flex;align-items:center;gap:12px;background:var(--card);border:1px solid var(--line);
                  border-left:3px solid var(--crit);border-radius:10px;padding:13px 16px;margin-bottom:16px}
          /* The header holds a lot for its height; shed the least important
             parts before anything is allowed to wrap onto a second line. */
          @media(max-width:900px){ .age{display:none} }
          /* The status word always stays — a bare coloured dot would leave state
             carried by colour alone. Shed padding, never the label. */
          @media(max-width:620px){ header{padding:11px 14px;gap:9px} .pill{padding:4px 8px} }
          @media(max-width:760px){.files{grid-template-columns:1fr}main{padding:16px 14px 30px}}
        </style>
        <header>
          <span class="brand">ALMIN</span>
          <span class="pill" id="status"><span class="dot"></span><span id="statustext">…</span></span>
          <span class="spacer"></span>
          <span class="age" id="age">connecting…</span>
          <button class="btn danger" id="srvstop" style="display:none">Stop server</button>
          <button class="btn go" id="srvstart" style="display:none">Start server</button>
          <button class="btn" id="logout" style="display:none">Log out</button>
        </header>
        <nav id="nav"></nav>
        <main id="main"></main>
        <script>
        const $ = id => document.getElementById(id);
        let authed=false, secure=false, pwSet=false, publicMetrics=true;
        let serverRunning=true, canStart=false, supervisor=false;
        let tab='dash', last=null, stuck=true, tpsHistory=[];

        const esc = s => (s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
        async function jget(u){ const r=await fetch(u,{credentials:'same-origin'});
          return {status:r.status, body:await r.json().catch(()=>({}))}; }
        async function jpost(u,d){ const r=await fetch(u,{method:'POST',credentials:'same-origin',
            headers:{'Content-Type':'application/json'},body:JSON.stringify(d||{})});
          return {status:r.status, body:await r.json().catch(()=>({}))}; }

        // ---- tiles ----
        function tpsState(tps,target){
          if(tps >= target-0.5) return ['good','Healthy'];
          if(tps >= target*0.75) return ['warn','Strained'];
          return ['crit','Critical'];
        }
        function memState(p){
          if(p>=90) return ['crit','Critical'];
          if(p>=75) return ['warn','High'];
          return ['good','Normal'];
        }
        function tile(cls,cap,big,sub,stateWord,meterPct,meterCls){
          const d=document.createElement('div');
          d.className='tile'+(cls?' '+cls:'');
          let h='<div class="cap">'+esc(cap)+'</div><div class="big num">'+esc(big)+'</div>';
          const bits=[];
          if(stateWord) bits.push('<span class="state '+cls+'">'+esc(stateWord)+'</span>');
          if(sub) bits.push('<span>'+esc(sub)+'</span>');
          if(bits.length) h+='<div class="sub">'+bits.join(' &middot; ')+'</div>';
          if(meterPct!=null) h+='<div class="meter"><i class="'+(meterCls||'')+'" style="width:'+
             Math.max(0,Math.min(100,meterPct))+'%"></i></div>';
          d.innerHTML=h;
          return d;
        }
        // One series, so no legend: the caption names it. 2px line, dot on the last sample.
        function sparkline(values,color){
          const wrap=document.createElement('div'); wrap.className='sparkwrap';
          if(values.length<2) return wrap;
          const W=200,H=32,pad=3;
          const min=Math.min(...values), max=Math.max(...values);
          const span=(max-min)||1;
          const pts=values.map((v,i)=>{
            const x=pad+(i/(values.length-1))*(W-pad*2);
            const y=H-pad-((v-min)/span)*(H-pad*2);
            return [x,y];
          });
          const d=pts.map((p,i)=>(i?'L':'M')+p[0].toFixed(1)+' '+p[1].toFixed(1)).join(' ');
          const area='M'+pts[0][0].toFixed(1)+' '+H+' '+pts.map(p=>'L'+p[0].toFixed(1)+' '+p[1].toFixed(1)).join(' ')
            +' L'+pts[pts.length-1][0].toFixed(1)+' '+H+' Z';
          const lastPt=pts[pts.length-1];
          wrap.innerHTML='<svg class="spark" viewBox="0 0 '+W+' '+H+'" preserveAspectRatio="none" '+
            'role="img" aria-label="Recent TPS trend">'+
            '<defs><linearGradient id="sg" x1="0" x2="0" y1="0" y2="1">'+
            '<stop offset="0%" stop-color="'+color+'" stop-opacity=".28"/>'+
            '<stop offset="100%" stop-color="'+color+'" stop-opacity="0"/></linearGradient></defs>'+
            '<path d="'+area+'" fill="url(#sg)"/>'+
            '<path d="'+d+'" fill="none" stroke="'+color+'" stroke-width="2" '+
            'stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/>'+
            '<circle cx="'+lastPt[0].toFixed(1)+'" cy="'+lastPt[1].toFixed(1)+'" r="2.5" fill="'+color+'"/>'+
            '</svg><div class="sparktip" id="sparktip"></div>';
          // Hover readout: nearest sample, value + how long ago.
          const svg=wrap.querySelector('svg'), tip=wrap.querySelector('.sparktip');
          svg.addEventListener('mousemove',e=>{
            const r=svg.getBoundingClientRect();
            const frac=Math.max(0,Math.min(1,(e.clientX-r.left)/r.width));
            const i=Math.round(frac*(values.length-1));
            const agoS=(values.length-1-i)*3;
            tip.textContent=values[i].toFixed(2)+' TPS · '+(agoS===0?'now':agoS+'s ago');
            tip.style.left=(frac*r.width)+'px'; tip.style.top=(H-4)+'px'; tip.style.opacity='1';
          });
          svg.addEventListener('mouseleave',()=>{ tip.style.opacity='0'; });
          return wrap;
        }
        function buildTiles(m){
          const wrap=document.createElement('div'); wrap.className='tiles';
          if(!m) return wrap;
          const [tc,tw]=tpsState(m.tps,m.tpsTarget);
          const tpsTile=tile(tc,'Ticks per second',m.tps.toFixed(2),
            'target '+m.tpsTarget+' · '+m.mspt.toFixed(1)+' ms',tw,null,null);
          const col=tc==='good'?'#0ca30c':tc==='warn'?'#fab219':'#d03b3b';
          if(tpsHistory.length>1) tpsTile.appendChild(sparkline(tpsHistory,col));
          wrap.appendChild(tpsTile);

          const pPct=m.maxPlayers>0?(m.players/m.maxPlayers)*100:0;
          wrap.appendChild(tile('','Players online',m.players+' / '+m.maxPlayers,
            m.players===1?'1 player connected':m.players+' players connected',null,pPct,''));

          const [mc,mw]=memState(m.memPct);
          wrap.appendChild(tile(mc,'Memory',m.memPct+'%',m.memUsed+' of '+m.memMax,mw,m.memPct,mc));

          wrap.appendChild(tile('','Uptime',m.uptime,
            (m.chunks!=null? m.chunks.toLocaleString()+' chunks · '+m.entities.toLocaleString()+' entities':''),
            null,null,null));
          return wrap;
        }

        function rowsToGrid(rows){
          const grid=document.createElement('div'); grid.className='grid'; let sec=null;
          for(const r of rows){
            if(r.kind===0){ sec=document.createElement('section');
              const h=document.createElement('h2'); h.textContent=r.label; sec.appendChild(h);
              grid.appendChild(sec); }
            else if(sec){ const d=document.createElement('div');
              if(r.kind===2){ d.className='note'; d.textContent=r.label; }
              else { d.className='row';
                const k=document.createElement('span'); k.className='k'; k.textContent=r.label;
                const v=document.createElement('span'); v.className='v'; v.textContent=r.value;
                if(r.accent) v.style.color=r.accent; d.append(k,v); }
              sec.appendChild(d); } }
          return grid;
        }

        function setChrome(){
          const st=$('status'), txt=$('statustext');
          st.className='pill '+(serverRunning?'up':'down');
          txt.textContent=serverRunning?'Online':'Stopped';
          st.title=serverRunning?'Minecraft server is running':'Minecraft server is stopped';
          $('logout').style.display=authed?'':'none';
          $('srvstop').style.display=(authed&&serverRunning)?'':'none';
          $('srvstart').style.display=(authed&&!serverRunning)?'':'none';
          $('srvstart').disabled=!canStart;
          $('srvstart').title=canStart?'':'Set web-supervisor and web-start-command to enable';
          const nav=$('nav'); nav.innerHTML='';
          const tabs = authed ? [['dash','Overview'],['log','Console'],['term','Terminal'],['files','Files'],['mods','Mods']]
                              : [['dash','Overview']];
          for(const [id,label] of tabs){
            const b=document.createElement('button'); b.textContent=label; b.className=(id===tab?'on':'');
            b.onclick=()=>{ tab=id; render(); }; nav.appendChild(b);
          }
        }

        function render(){
          setChrome();
          const m=$('main'); m.innerHTML='';
          if(!authed && tab!=='dash') tab='dash';
          if(tab==='dash') m.appendChild(dashPanel());
          else if(tab==='log') m.appendChild(consolePanel());
          else if(tab==='term') m.appendChild(termPanel());
          else if(tab==='files') m.appendChild(filesPanel());
          else if(tab==='mods') m.appendChild(modsPanel());
        }

        function dashPanel(){
          const wrap=document.createElement('div');
          if(!serverRunning){
            const b=document.createElement('div'); b.className='banner';
            b.innerHTML='<span class="state crit">Stopped</span><span class="muted">'+
              'The Minecraft server is not running. '+
              (authed?(canStart?'Use <b>Start server</b> above.':'No start command is configured.')
                     :'Live metrics resume when it starts.')+'</span>';
            wrap.appendChild(b);
          }
          const metrics=document.createElement('div'); metrics.id='metrics';
          if(last) metrics.appendChild(paint(last));
          wrap.appendChild(metrics);
          if(!authed) wrap.appendChild(loginBox());
          return wrap;
        }
        function paint(d){
          const frag=document.createDocumentFragment();
          if(serverRunning && d.metrics) frag.appendChild(buildTiles(d.metrics));
          if(d.rows && d.rows.length) frag.appendChild(rowsToGrid(d.rows));
          return frag;
        }
        function updateMetrics(){
          const m=$('metrics'); if(!m) return;
          m.innerHTML=''; if(last) m.appendChild(paint(last));
        }
        function loginBox(){
          const box=document.createElement('div'); box.className='login';
          box.insertAdjacentHTML('beforeend','<h2>Admin login</h2>');
          if(!pwSet){ box.insertAdjacentHTML('beforeend',
            '<p class="muted">No admin password is set yet. In game, run '+
            '<code>/almin op web password &lt;password&gt;</code>.</p>'); return box; }
          if(!secure){ box.insertAdjacentHTML('beforeend',
            '<p class="muted">Log in over the HTTPS address. This connection isn\\'t recognised as secure.</p>');
            return box; }
          const pw=document.createElement('input'); pw.type='password'; pw.placeholder='Admin password';
          pw.autocomplete='current-password';
          const btn=document.createElement('button'); btn.className='btn'; btn.textContent='Log in';
          const msg=document.createElement('div'); msg.className='msg err';
          pw.onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); doLogin(pw.value,msg); } };
          btn.onclick=()=>doLogin(pw.value,msg);
          box.append(pw,btn,msg);
          return box;
        }
        async function doLogin(pw,msg){
          msg.textContent='';
          const r=await jpost('/api/login',{password:pw});
          if(r.status===200){ authed=true; await refreshOnce(); tab='dash'; render(); }
          else if(r.status===429) msg.textContent='Too many attempts — locked out for '+(r.body.minutes||15)+' min.';
          else if(r.body&&r.body.remaining!=null) msg.textContent='Wrong password. '+r.body.remaining+' attempt(s) left.';
          else msg.textContent='Login failed.';
        }

        function consolePanel(){
          const wrap=document.createElement('div');
          const pre=document.createElement('pre'); pre.id='log'; wrap.appendChild(pre);
          pre.addEventListener('scroll',()=>{ stuck = pre.scrollTop+pre.clientHeight >= pre.scrollHeight-24; });
          loadConsole();
          return wrap;
        }
        async function loadConsole(){
          if(tab!=='log'&&tab!=='term') return;
          if(!serverRunning) return;
          const r=await jget('/api/console'); const pre=$('log'); if(!pre) return;
          pre.innerHTML=(r.body.lines||[]).map(l=>{
            const c=/\\/ERROR\\]| ERROR /.test(l)?'errline':/\\/WARN\\]| WARN /.test(l)?'warnline':'';
            return c?'<span class="'+c+'">'+esc(l)+'</span>':esc(l); }).join('\\n');
          if(stuck) pre.scrollTop=pre.scrollHeight;
        }

        function termPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">Runs a server command as the console (same as '+
            '<code>/almin op cmd</code>). Output appears below and in the Console tab.</p>';
          const bar=document.createElement('div'); bar.className='term';
          const inp=document.createElement('input'); inp.placeholder='say hello   (no leading slash needed)';
          const btn=document.createElement('button'); btn.className='btn'; btn.textContent='Run';
          const msg=document.createElement('div'); msg.className='msg';
          const run=async()=>{ const c=inp.value.trim(); if(!c) return;
            const r=await jpost('/api/exec',{command:c});
            msg.className='msg '+(r.status===200?'ok':'err');
            msg.textContent = r.status===200 ? 'ran: /'+r.body.ran : (r.body.error||'failed ('+r.status+')');
            inp.value=''; setTimeout(loadConsole,300); };
          inp.onkeydown=e=>{ if(e.key==='Enter'){ e.preventDefault(); run(); } }; btn.onclick=run;
          const pre=document.createElement('pre'); pre.id='log';
          bar.append(inp,btn); wrap.append(bar,msg,pre);
          loadConsole();
          return wrap;
        }

        let curDir='';
        function filesPanel(){
          const wrap=document.createElement('div'); wrap.className='files';
          const listBox=document.createElement('div'); listBox.className='flist'; listBox.id='flist';
          const ed=document.createElement('div'); ed.className='editor';
          ed.innerHTML='<div class="bar"><input id="fpath" placeholder="path under a writable root, e.g. config/almin/config.json">'+
            '<button class="btn" id="fsave">Save</button><button class="btn danger" id="fdel">Delete</button>'+
            '<button class="btn" id="fren">Rename</button></div>'+
            '<textarea id="fbody" placeholder="Select a file to edit, or type a path above and Save to create one."></textarea>'+
            '<div class="msg" id="fmsg"></div>';
          wrap.append(listBox,ed);
          setTimeout(()=>{ loadDir(curDir);
            $('fsave').onclick=saveFile; $('fdel').onclick=delFile; $('fren').onclick=renFile; },0);
          return wrap;
        }
        async function loadDir(path){
          curDir=path;
          const r=await jget('/api/files?path='+encodeURIComponent(path));
          const box=$('flist'); if(!box) return; box.innerHTML='';
          if(r.status!==200){ box.innerHTML='<div class="note">'+esc(r.body.error||'unavailable')+'</div>'; return; }
          const crumb=document.createElement('div'); crumb.className='fentry';
          crumb.innerHTML='<b>'+(path? esc('/'+path) : '/ (server root)')+'</b>'; box.appendChild(crumb);
          if(path){ const up=document.createElement('div'); up.className='fentry dir';
            up.textContent='↑ up'; up.onclick=()=>loadDir(path.split('/').slice(0,-1).join('/')); box.appendChild(up); }
          for(const e of (r.body.entries||[])){
            const row=document.createElement('div'); row.className='fentry'+(e.directory?' dir':'');
            const full = path ? path+'/'+e.name : e.name;
            row.innerHTML='<span>'+(e.directory?'📁':'📄')+' '+esc(e.name)+'</span>'+
              (e.directory?'':'<span class="sz">'+(e.size>=0?e.size+' B':'')+'</span>');
            row.onclick=()=> e.directory ? loadDir(full) : openFile(full);
            box.appendChild(row);
          }
        }
        async function openFile(path){
          const r=await jget('/api/file?path='+encodeURIComponent(path));
          const msg=$('fmsg');
          if(r.status!==200){ msg.className='msg err'; msg.textContent=r.body.error||'could not open'; return; }
          $('fpath').value=path; $('fbody').value=r.body.content; msg.className='msg'; msg.textContent='';
        }
        async function saveFile(){
          const r=await jpost('/api/file',{path:$('fpath').value,content:$('fbody').value});
          const msg=$('fmsg'); msg.className='msg '+(r.body.ok?'ok':'err');
          msg.textContent=r.body.ok?'saved':(r.body.message||r.body.error||'save failed');
          if(r.body.ok) loadDir(curDir);
        }
        async function delFile(){
          const p=$('fpath').value; if(!p) return;
          if(!confirm('Delete '+p+'?')) return;
          const r=await jpost('/api/file/delete',{path:p});
          const msg=$('fmsg'); msg.className='msg '+(r.body.ok?'ok':'err');
          msg.textContent=r.body.ok?'deleted':(r.body.message||r.body.error||'delete failed');
          if(r.body.ok){ $('fbody').value=''; $('fpath').value=''; loadDir(curDir); }
        }
        async function renFile(){
          const p=$('fpath').value; if(!p) return;
          const name=prompt('New name for '+p.split('/').pop()+':'); if(!name) return;
          const r=await jpost('/api/file/rename',{path:p,name:name});
          const msg=$('fmsg'); msg.className='msg '+(r.body.ok?'ok':'err');
          msg.textContent=r.body.ok?'renamed':(r.body.message||r.body.error||'rename failed');
          if(r.body.ok) loadDir(curDir);
        }

        // ---- server control ----
        $('srvstop').onclick=async()=>{
          if(!confirm('Stop the Minecraft server?\\n\\nPlayers will be disconnected.')) return;
          $('srvstop').disabled=true;
          const r=await jpost('/api/server',{action:'stop'});
          if(r.status!==200){ alert(r.body.error||'Stop failed'); $('srvstop').disabled=false; }
        };
        $('srvstart').onclick=async()=>{
          if(!canStart) return;
          if(!confirm('Start the Minecraft server?\\n\\nThe panel restarts with it and may be '+
                      'briefly unreachable.')) return;
          $('srvstart').disabled=true;
          const r=await jpost('/api/server',{action:'start'});
          if(r.status!==200){ alert(r.body.error||'Start failed'); $('srvstart').disabled=false; }
          else $('age').textContent='starting server…';
        };

        // ---- advertised mods ----
        function modsPanel(){
          const wrap=document.createElement('div');
          wrap.innerHTML='<p class="muted">Mods offered to players when they join. '+
            'Nothing is pushed &mdash; each player sees this list and chooses. '+
            'Prefer uploading the jar here: players then fetch it straight from this server. '+
            'External URLs must be <code>https://</code>; a SHA-256 pins the exact file.</p>'+
            '<div id="modsettings" class="muted" style="margin-bottom:10px"></div>'+
            '<div id="modlist"></div>'+
            '<section style="margin-top:14px"><h2>Upload a jar to this server</h2>'+
            '<p class="muted">Stored in <code>config/almin/modfiles/</code>. Players download it '+
            'over their game connection &mdash; no public link, nothing else to host.</p>'+
            '<input type="file" id="m-file" accept=".jar">'+
            '<button class="btn" id="m-upload" style="margin-top:8px">Upload</button>'+
            '<div class="msg" id="m-upmsg"></div>'+
            '<div id="m-files" style="margin-top:10px"></div></section>'+
            '<section style="margin-top:14px"><h2>Advertise a mod</h2>'+
            '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">'+
            '<input id="m-id" placeholder="mod id (e.g. sodium)">'+
            '<input id="m-name" placeholder="display name (optional)">'+
            '<input id="m-ver" placeholder="version (optional)">'+
            '<input id="m-sha" placeholder="sha256 (optional, recommended)">'+
            '</div>'+
            '<p class="muted" style="margin:10px 0 4px">Source &mdash; pick an uploaded file, '+
            'or leave it on &ldquo;URL&rdquo; and paste an https link.</p>'+
            '<select id="m-src" style="width:100%;background:#0b0d11;border:1px solid var(--line);'+
            'color:var(--ink);border-radius:8px;padding:9px 11px;font:inherit"></select>'+
            '<input id="m-url" placeholder="https://... direct link to the .jar" style="margin-top:8px">'+
            '<label class="muted" style="display:flex;gap:8px;align-items:center;margin-top:8px">'+
            '<input type="checkbox" id="m-req" style="width:auto"> Required '+
            '(declining can disconnect, if mods-deny-kicks is on)</label>'+
            '<button class="btn" id="m-save" style="margin-top:10px">Save mod</button>'+
            '<div class="msg" id="m-msg"></div></section>';
          setTimeout(()=>{ loadMods(); loadModFiles();
            $('m-save').onclick=saveMod; $('m-upload').onclick=uploadMod;
            $('m-src').onchange=()=>{ $('m-url').style.display=$('m-src').value?'none':''; }; },0);
          return wrap;
        }
        async function loadMods(){
          const r=await jget('/api/mods');
          const box=$('modlist'); if(!box) return;
          if(r.status!==200){ box.innerHTML='<div class="note">'+esc(r.body.error||'unavailable')+'</div>'; return; }
          const s=$('modsettings');
          if(s) s.innerHTML='advertising: <b>'+(r.body.advertise?'on':'off')+'</b> &middot; '+
            'deny disconnects: <b>'+(r.body.denyKicks?'yes':'no')+'</b> &middot; '+
            'client mod required: <b>'+(r.body.requireClientMod?'yes':'no')+'</b> '+
            '<span style="opacity:.7">(change these under Overview &rarr; /almin config)</span>';
          const mods=r.body.mods||[];
          if(!mods.length){ box.innerHTML='<div class="note">Nothing advertised yet.</div>'; return; }
          box.innerHTML='';
          const sec=document.createElement('section');
          sec.innerHTML='<h2>Advertised ('+mods.length+')</h2>';
          for(const m of mods){
            const row=document.createElement('div'); row.className='row';
            const left=document.createElement('span'); left.className='k';
            left.innerHTML='<b style="color:var(--ink)">'+esc(m.name||m.id)+'</b>'+
              (m.version?' <span class="muted">'+esc(m.version)+'</span>':'')+
              (m.required?' <span class="state warn">REQUIRED</span>':' <span class="muted">optional</span>')+
              (m.sha256?' <span class="muted">&middot; pinned</span>':'')+
              '<br><span class="muted" style="font-size:12px">'+
              (m.file? 'served by this server &middot; modfiles/'+esc(m.file) : esc(m.url))+'</span>';
            const btn=document.createElement('button'); btn.className='btn danger'; btn.textContent='Remove';
            btn.style.marginLeft='auto';
            btn.onclick=async()=>{ if(!confirm('Stop advertising '+m.id+'?')) return;
              const d=await jpost('/api/mods/delete',{id:m.id});
              if(d.status!==200) alert(d.body.error||'remove failed'); loadMods(); };
            const edit=document.createElement('button'); edit.className='btn'; edit.textContent=m.required?'Make optional':'Make required';
            edit.onclick=async()=>{ const d=await jpost('/api/mods/save',{
                id:m.id,name:m.name,version:m.version,url:m.url,file:m.file,
                sha256:m.sha256,required:!m.required});
              if(d.status!==200) alert(d.body.error||'update failed'); loadMods(); };
            row.append(left,edit,btn);
            row.style.gap='8px'; row.style.alignItems='center';
            sec.appendChild(row);
          }
          box.appendChild(sec);
        }
        async function loadModFiles(){
          const r=await jget('/api/mods/files');
          const sel=$('m-src'), box=$('m-files');
          if(!sel) return;
          const files=(r.status===200 && r.body.files)?r.body.files:[];
          sel.innerHTML='<option value="">URL (external https link)</option>'+
            files.map(f=>'<option value="'+esc(f)+'">server file: '+esc(f)+'</option>').join('');
          $('m-url').style.display=sel.value?'none':'';
          if(box){
            box.innerHTML = files.length
              ? '<div class="muted">On this server: '+files.map(f=>
                  '<span style="display:inline-flex;gap:6px;align-items:center;margin:2px 8px 2px 0">'+
                  esc(f)+' <a href="#" data-f="'+esc(f)+'" class="delfile" style="color:#e97070">remove</a></span>').join('')+'</div>'
              : '<div class="note">No jars uploaded yet.</div>';
            box.querySelectorAll('.delfile').forEach(a=>a.onclick=async e=>{
              e.preventDefault();
              const f=a.getAttribute('data-f');
              if(!confirm('Delete '+f+' from the server?')) return;
              const d=await jpost('/api/mods/files/delete',{name:f});
              if(d.status!==200) alert(d.body.error||'delete failed');
              loadModFiles(); loadMods(); });
          }
        }
        async function uploadMod(){
          const inp=$('m-file'), msg=$('m-upmsg');
          if(!inp.files || !inp.files.length){ msg.className='msg err'; msg.textContent='Choose a .jar first.'; return; }
          const f=inp.files[0];
          msg.className='msg'; msg.textContent='Uploading '+f.name+'…';
          try{
            const r=await fetch('/api/mods/upload?name='+encodeURIComponent(f.name),
              {method:'POST',credentials:'same-origin',
               headers:{'Content-Type':'application/octet-stream'},body:f});
            const b=await r.json().catch(()=>({}));
            msg.className='msg '+(r.status===200?'ok':'err');
            msg.textContent = r.status===200 ? ('uploaded '+b.name+' ('+b.bytes+' bytes)')
                                             : (b.error||'upload failed');
            if(r.status===200){ inp.value=''; loadModFiles(); }
          }catch(e){ msg.className='msg err'; msg.textContent='upload failed'; }
        }
        async function saveMod(){
          const msg=$('m-msg');
          const src=$('m-src').value;
          const r=await jpost('/api/mods/save',{
            id:$('m-id').value.trim(), name:$('m-name').value.trim(),
            version:$('m-ver').value.trim(), sha256:$('m-sha').value.trim(),
            file:src, url:src?'':$('m-url').value.trim(), required:$('m-req').checked});
          msg.className='msg '+(r.status===200?'ok':'err');
          msg.textContent = r.status===200 ? 'saved' : (r.body.error||'save failed');
          if(r.status===200){ ['m-id','m-name','m-ver','m-sha','m-url'].forEach(i=>$(i).value='');
            $('m-req').checked=false; $('m-src').value=''; $('m-url').style.display='';
            loadMods(); loadModFiles(); }
        }

        // ---- polling ----
        async function refreshOnce(){
          const s=await jget('/api/session');
          if(s.status!==200) return;
          authed=!!s.body.authed; secure=!!s.body.secure; pwSet=!!s.body.passwordSet;
          publicMetrics=!!s.body.publicMetrics; canStart=!!s.body.canStart;
          supervisor=!!s.body.supervisor;
          if(s.body.serverRunning!=null) serverRunning=!!s.body.serverRunning;
        }
        async function poll(){
          const wasAuthed=authed, wasRunning=serverRunning;
          try { await refreshOnce(); }
          catch(e){ $('age').textContent='panel unreachable'; return; }
          let d=null;
          if(authed){ const r=await jget('/api/state'); if(r.status===200) d=r.body; }
          else if(publicMetrics){ const r=await jget('/api/public'); if(r.status===200) d=r.body; }
          if(d){
            last=d;
            if(d.metrics && serverRunning){
              tpsHistory.push(d.metrics.tps);
              if(tpsHistory.length>40) tpsHistory.shift();
            }
            const secs=Math.max(0,Math.round((Date.now()-d.generated)/1000));
            $('age').textContent='updated '+(secs<2?'just now':secs+'s ago');
          }
          // Only rebuild the whole panel when login or server state flips, so a
          // half-typed password or a scrolled console isn't thrown away.
          if(authed!==wasAuthed || serverRunning!==wasRunning){
            if(!authed) tab='dash';
            if(!serverRunning) tpsHistory=[];
            render(); return;
          }
          setChrome();
          if(tab==='dash') updateMetrics();
          else if(tab==='log'||tab==='term') loadConsole();
        }
        $('logout').onclick=async()=>{ await jpost('/api/logout',{}); authed=false; tab='dash'; last=null; render(); };
        (async()=>{ await refreshOnce(); render(); poll(); setInterval(poll,3000); })();
        </script>
        """;
}
