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
import java.util.concurrent.ExecutorService;
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

    /**
     * Kept so the panel can be started again after being stopped from the Web
     * tab, without waiting for a server restart.
     */
    private static volatile MinecraftServer boundServer;

    /**
     * Why the last start attempt failed, or "" if it didn't. Almin's own log
     * deliberately never reaches the server console, which means a panel that
     * failed to bind used to look exactly like a panel nobody turned on. This
     * is what the Web tab and {@code /almin op web} show instead.
     */
    private static volatile String lastError = "";

    /** The tick hook is registered once per JVM, not once per start. */
    private static boolean tickHooked = false;

    /** The JVM shutdown hook is registered once per JVM too. */
    private static boolean shutdownHooked = false;

    /**
     * False once the Minecraft server has stopped. A panel started after that
     * — supervisor mode allows it — must inherit the truth rather than assume
     * a live server it would then try to read.
     */
    private static volatile boolean serverUp = true;

    /**
     * The one Almin logger that writes to the real server console. Everything
     * else stays in config/almin/almin.log; a web panel that silently never
     * came up is the one failure an owner has no other way to discover.
     */
    private static final org.slf4j.Logger CONSOLE = org.slf4j.LoggerFactory.getLogger("almin");

    /** Ports tried before giving up, when the configured one is already taken. */
    private static final int PORT_ATTEMPTS = 12;

    /** Attempts on the configured port before looking elsewhere, and the wait between. */
    private static final int PREFERRED_RETRIES = 4;
    private static final long PREFERRED_RETRY_MS = 750;

    /** Stdin for a handed-off server. See {@link #handOffTo}. */
    private static final ProcessBuilder.Redirect NULL_INPUT =
        ProcessBuilder.Redirect.from(new java.io.File("/dev/null"));

    private final HttpServer http;
    /**
     * Kept because {@link HttpServer#stop} deliberately does not shut down an
     * executor it was given. Without this, every panel restart left four
     * threads behind.
     */
    private final ExecutorService pool;
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

    /**
     * Set by the panel's Restart when it can relaunch: run the start command as
     * soon as the stop completes, instead of leaving the server down.
     */
    private volatile boolean restartAfterStop = false;

    private WebUi(HttpServer http, ExecutorService pool, MinecraftServer server, String bind, int port) {
        this.http = http;
        this.pool = pool;
        this.server = server;
        this.bind = bind;
        this.port = port;
    }

    // ---------- lifecycle ----------

    /** Outcome of a start/stop/restart request, for whoever asked. */
    public record Control(boolean ok, String message) {}

    /** Starts the panel if enabled. Never throws — a bind failure just logs. */
    public static synchronized void start(MinecraftServer server) {
        boundServer = server;
        serverUp = true;
        hookTick();
        if (instance != null) return;
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.webUiEnabled) {
            lastError = "";
            AlminLog.info("[almin] web panel disabled by config");
            CONSOLE.info("[almin] web panel is off (web-ui-enabled false)");
            return;
        }
        listen(server, cfg);
    }

    /**
     * Binds and starts serving, trying nearby ports when the configured one is
     * taken.
     *
     * <p>The panel picks its port at random on first run, so two Minecraft
     * instances on one host can land on the same number; before this, the
     * second one simply never came up and said so only in a log file nobody
     * was looking at. A port that works is written back to the config, so
     * bookmarks and the next boot agree with reality.
     */
    private static void listen(MinecraftServer server, AlminConfig cfg) {
        hookShutdown();
        String bind = (cfg.webUiBind == null || cfg.webUiBind.isBlank()) ? "0.0.0.0" : cfg.webUiBind.trim();
        boolean firstRun = cfg.webUiPort <= 0;
        int wanted = firstRun ? 8100 + new java.security.SecureRandom().nextInt(900) : cfg.webUiPort;

        // A predecessor that has only just died can still hold the socket for a
        // moment. Waiting beats moving: the configured port is the address
        // people have bookmarked.
        IOException first = null;
        for (int attempt = 0; attempt < PREFERRED_RETRIES; attempt++) {
            try {
                bindOnOwnThread(server, cfg, bind, wanted);
                if (firstRun) {
                    cfg.webUiPort = wanted;      // remember the first-run pick
                    AlminConfig.save();
                }
                return;
            } catch (java.net.BindException e) {
                if (first == null) first = e;
                if (attempt < PREFERRED_RETRIES - 1) sleep(PREFERRED_RETRY_MS);
            } catch (IOException e) {
                fail("could not bind " + bind + ":" + wanted, e.getMessage());
                return;
            } catch (RuntimeException e) {
                fail("failed to start", e.toString());
                return;
            }
        }

        // Still taken. Fall back so the panel is at least reachable, but do NOT
        // write the fallback to the config: the configured port is the operator's
        // intent, and persisting each fallback is what made the address crawl
        // upwards every restart.
        for (int step = 1; step < PORT_ATTEMPTS; step++) {
            int port = nextPort(wanted, step);
            try {
                bindOnOwnThread(server, cfg, bind, port);
                CONSOLE.warn("[almin] web panel port {} is still held by something "
                    + "(a previous instance?) — running on {} for now, "
                    + "config keeps {}", wanted, port, wanted);
                AlminLog.warn("[almin] port {} taken, using {} for this run only", wanted, port);
                return;
            } catch (java.net.BindException ignored) {
                // try the next one
            } catch (IOException e) {
                fail("could not bind " + bind + ":" + port, e.getMessage());
                return;
            } catch (RuntimeException e) {
                fail("failed to start", e.toString());
                return;
            }
        }
        fail("could not bind " + bind + ":" + wanted + " (or the " + (PORT_ATTEMPTS - 1)
            + " ports after it)", first == null ? "" : first.getMessage());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int nextPort(int wanted, int step) {
        int p = wanted + step;
        return p > 65535 ? 8100 + step : p;
    }

    /** Records a start failure everywhere someone might look for it. */
    private static void fail(String what, String detail) {
        lastError = (detail == null || detail.isBlank()) ? what : what + " — " + detail;
        AlminLog.warn("[almin] web panel {}", lastError);
        CONSOLE.warn("[almin] web panel did not start: {}", lastError);
    }

    private static void bindOn(MinecraftServer server, AlminConfig cfg, String bind, int port)
            throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 32);
        ExecutorService pool = Executors.newFixedThreadPool(4, threadFactory(cfg.webSupervisor));
        WebUi ui = new WebUi(http, pool, server, bind, port);
        http.createContext("/", guard("/", ui::handleRoot));
        http.createContext("/api/session", guard("/api/session", ui::handleSession));
        http.createContext("/api/public", guard("/api/public", ui::handlePublic));
        http.createContext("/api/login", guard("/api/login", ui::handleLogin));
        http.createContext("/api/logout", guard("/api/logout", ui::handleLogout));
        http.createContext("/api/state", guard("/api/state", ui::handleState));
        http.createContext("/api/console", guard("/api/console", ui::handleConsole));
        http.createContext("/api/exec", guard("/api/exec", ui::handleExec));
        http.createContext("/api/files", guard("/api/files", ui::handleFiles));
        http.createContext("/api/file", guard("/api/file", ui::handleFile));
        http.createContext("/api/file/delete", guard("/api/file/delete", ui::handleFileDelete));
        http.createContext("/api/file/rename", guard("/api/file/rename", ui::handleFileRename));
        http.createContext("/api/server", guard("/api/server", ui::handleServerControl));
        http.createContext("/api/mods", guard("/api/mods", ui::handleMods));
        http.createContext("/api/mods/save", guard("/api/mods/save", ui::handleModSave));
        http.createContext("/api/mods/delete", guard("/api/mods/delete", ui::handleModDelete));
        http.createContext("/api/mods/files", guard("/api/mods/files", ui::handleModFiles));
        http.createContext("/api/mods/upload", guard("/api/mods/upload", ui::handleModUpload));
        http.createContext("/api/mods/files/delete", guard("/api/mods/files/delete", ui::handleModFileDelete));
        http.createContext("/api/config", guard("/api/config", ui::handleConfig));
        http.createContext("/api/config/reload", guard("/api/config/reload", ui::handleConfigReload));
        http.createContext("/api/password", guard("/api/password", ui::handlePassword));
        http.createContext("/api/update", guard("/api/update", ui::handleUpdate));
        http.createContext("/api/clearlog", guard("/api/clearlog", ui::handleClearLog));
        http.createContext("/api/players", guard("/api/players", ui::handlePlayers));
        http.createContext("/api/mask", guard("/api/mask", ui::handleMask));
        http.createContext("/api/file/upload", guard("/api/file/upload", ui::handleFileUpload));
        http.createContext("/api/file/download", guard("/api/file/download", ui::handleFileDownload));
        http.createContext("/api/fetch", guard("/api/fetch", ui::handleFetch));
        http.createContext("/api/activity", guard("/api/activity", ui::handleActivity));
        http.createContext("/api/track", guard("/api/track", ui::handleTrack));
        // In supervisor mode the web threads must be non-daemon, or the JVM
        // exits the moment the server thread ends and takes the panel with it.
        http.setExecutor(pool);
        http.start();
        ui.serverRunning = serverUp;
        instance = ui;
        lastError = "";
        try {
            if (!serverUp) {
                ui.publicJson = stoppedJson();
                ui.fullJson = stoppedJson();
            }
            ui.rebuild();
            ui.writeCaddyfile(cfg);
        } catch (RuntimeException e) {
            // The panel is already listening. A failed first snapshot or an
            // unwritable Caddyfile is not a reason to tear it back down.
            AlminLog.warn("[almin] web panel is up, but post-start setup failed: {}", e.toString());
        }
        boolean pw = cfg.webAdminPasswordHash != null && !cfg.webAdminPasswordHash.isBlank();
        AlminLog.info("[almin] web panel on http://{}:{} (public metrics {}, admin login {})",
            bind, port, cfg.webPublicMetrics ? "on" : "off",
            pw ? "ready" : "NO PASSWORD SET — run /almin op web password <pw>");
        CONSOLE.info("[almin] web panel on {}  ({})", browsableUrl(),
            pw ? "log in with your admin password"
               : "no password set yet — /almin op web password <pw>");
    }

    /**
     * Wraps a route so a fault becomes an error the browser can read.
     *
     * <p>{@code HttpServer} does not catch what a handler throws: the exchange
     * is closed with no status line at all, and every client sees the same
     * thing — a connection that died. In a browser {@code fetch} simply
     * rejects, so an upload that hit an unexpected NPE was indistinguishable
     * from one that was never sent, and the panel could only say "failed".
     *
     * <p>Now the reason reaches the person who caused it, and the log.
     */
    private static com.sun.net.httpserver.HttpHandler guard(
            String route, com.sun.net.httpserver.HttpHandler inner) {
        // route is only for readability at the call sites; fault() reads the
        // real path off the exchange.
        return ex -> {
            try {
                inner.handle(ex);
            } catch (Throwable t) {
                // Belt to the routes' own braces: this catches anything thrown
                // before a handler's try block was even entered.
                fault(ex, t);
                try {
                    ex.close();
                } catch (Throwable ignored) {
                    // Closing twice is fine.
                }
            }
        };
    }

    /**
     * Answers a request whose handler threw.
     *
     * <p>Called from each route's own catch, which has to run before the
     * {@code finally} that closes the exchange — once it is closed there is
     * nothing left to write a status onto, and the client sees a connection
     * that simply died. That is what an unexpected fault used to look like
     * from a browser: {@code fetch} rejects with no status and no message, so
     * the panel could only say "failed".
     */
    private static void fault(HttpExchange ex, Throwable t) {
        String route = ex.getRequestURI() == null ? "?" : ex.getRequestURI().getPath();
        AlminLog.warn("[almin] web route {} failed: {}", route, t.toString());
        CONSOLE.warn("[almin] web route {} failed", route, t);
        try {
            send(ex, 500, "application/json; charset=utf-8",
                err(route + " failed — " + describe(t)));
        } catch (Throwable ignored) {
            // Already answered, or the socket is gone.
        }
    }

    /** A one-line reason, since a stack trace is no use in a browser. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
            ? t.getClass().getSimpleName()
            : t.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Registers the snapshot tick once for the JVM and dispatches to whatever
     * instance exists. Registering per start would pile up a dead listener
     * behind every stop, and the panel can now be stopped and started at will.
     */
    private static synchronized void hookTick() {
        if (tickHooked) return;
        tickHooked = true;
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            WebUi ui = instance;
            if (ui != null) ui.onTick(s);
        });
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
        // Not shutdownNow(): the thread calling this may itself be a pool
        // thread serving the request that asked for the restart.
        ui.pool.shutdown();
    }

    /** Starts the panel on request, rather than at server start. */
    public static synchronized Control startNow() {
        if (instance != null) return new Control(false, "The web panel is already running.");
        MinecraftServer s = boundServer;
        if (s == null) return new Control(false, "The server isn't ready yet — try again in a moment.");
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.webUiEnabled) return new Control(false, "Turn Enabled on first.");
        lastError = "";
        listen(s, cfg);
        if (instance != null) return new Control(true, "Web panel started on " + browsableUrl());
        return new Control(false, lastError.isBlank() ? "The panel could not start." : lastError);
    }

    /** Stops the panel on request, leaving the Minecraft server running. */
    public static synchronized Control stopNow() {
        if (instance == null) return new Control(false, "The web panel is not running.");
        stop();
        AlminLog.info("[almin] web panel stopped on request");
        CONSOLE.info("[almin] web panel stopped");
        return new Control(true, "Web panel stopped.");
    }

    /** Stops then starts, so a changed port or bind address takes effect. */
    public static synchronized Control restartNow() {
        stop();
        return startNow();
    }

    public static boolean running()  { return instance != null; }

    /** Why the panel is not running, or "" if there is no recorded failure. */
    public static String lastError() { return lastError; }

    /**
     * A URL a person can actually paste into a browser. 0.0.0.0 means "every
     * interface", which is not an address you can visit, so it becomes a
     * placeholder for the server's own hostname.
     */
    public static String browsableUrl() {
        WebUi ui = instance;
        if (ui != null) return url(ui.bind, ui.port);
        // Not running: show the address it would use, which is what someone
        // reading the Web tab actually wants to know.
        AlminConfig cfg = AlminConfig.get();
        return url(cfg.webUiBind, cfg.webUiPort);
    }

    private static String url(String bind, int port) {
        String host = (bind == null || bind.isBlank() || bind.equals("0.0.0.0"))
            ? "<server-address>" : bind;
        return "http://" + host + ":" + port + "/";
    }
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
     * Binds and starts on a thread with the daemon flag we want the listener to
     * inherit.
     *
     * <p>{@code HttpServer.start()} spawns its own HTTP-Dispatcher thread, and
     * that thread is <em>not</em> covered by the executor set with
     * {@code setExecutor} — it inherits its daemon flag from whichever thread
     * called {@code start()}. Called from the Minecraft server thread, which is
     * not a daemon, the dispatcher was not either: when the game crashed
     * without running its shutdown hooks, that one thread kept the whole JVM
     * alive, holding the port open in front of a panel with a dead server
     * behind it. The next start then found its port taken by its own corpse.
     *
     * <p>So the bind happens on a short-lived thread whose daemon flag matches
     * {@code web-supervisor}: off (the default) means the JVM ends when
     * Minecraft does and the port goes with it; on means the panel is
     * deliberately outliving the server and must hold the JVM open.
     */
    private static void bindOnOwnThread(MinecraftServer server, AlminConfig cfg,
                                        String bind, int port) throws IOException {
        IOException[] failure = new IOException[1];
        RuntimeException[] crashed = new RuntimeException[1];
        Thread t = new Thread(() -> {
            try {
                bindOn(server, cfg, bind, port);
            } catch (IOException e) {
                failure[0] = e;
            } catch (RuntimeException e) {
                crashed[0] = e;
            }
        }, "Almin-web-bind");
        t.setDaemon(!cfg.webSupervisor);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while starting the web panel");
        }
        if (failure[0] != null) throw failure[0];
        if (crashed[0] != null) throw crashed[0];
    }

    /**
     * Closes the listener when the JVM goes down, whatever route it took there.
     * Registered once; with a daemon dispatcher the exit already frees the
     * port, but this makes it prompt and closes the pool with it.
     */
    private static synchronized void hookShutdown() {
        if (shutdownHooked) return;
        shutdownHooked = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(WebUi::stop, "Almin-web-shutdown"));
        } catch (IllegalStateException ignored) {
            // Already shutting down; nothing left to arrange.
        }
    }

    /**
     * Called when the Minecraft server has stopped. In supervisor mode the panel
     * stays up (showing the server as stopped, offering Start); otherwise it
     * shuts down with everything else.
     */
    public static void onServerStopped() {
        // Recorded even with no panel up: one started later must not think it
        // still has a live server behind it.
        serverUp = false;
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
        if (ui.restartAfterStop) {
            ui.restartAfterStop = false;
            String cmd = AlminConfig.get().webStartCommand;
            if (cmd != null && !cmd.isBlank()) ui.handOffTo(cmd.trim());
        }
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
            send(ex, 200, "text/html; charset=utf-8", WebPage.HTML);
        } catch (Throwable t) {
            fault(ex, t);
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
            o.addProperty("encrypted", isProtected(ex));
            o.addProperty("passwordSet", pwSet);
            o.addProperty("publicMetrics", cfg.webPublicMetrics);
            o.addProperty("serverRunning", serverRunning);
            o.addProperty("supervisor", cfg.webSupervisor);
            o.addProperty("canStart", cfg.webSupervisor
                && cfg.webStartCommand != null && !cfg.webStartCommand.isBlank());
            json(ex, 200, o.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private void handlePublic(HttpExchange ex) throws IOException {
        try {
            if (!AlminConfig.get().webPublicMetrics) { json(ex, 403, "{\"disabled\":true}"); return; }
            json(ex, 200, publicJson);
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private void handleState(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            json(ex, 200, fullJson);
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
            if (!secure(ex)) {
                json(ex, 403, err("This server only accepts admin logins over HTTPS or from the "
                    + "machine itself (web-require-secure is on). Put a TLS proxy in front, or set "
                    + "web-require-secure false."));
                return;
            }
            if (!isProtected(ex)) {
                AlminLog.warn("[almin] web admin login over plain HTTP from {} — "
                    + "set web-require-secure true once TLS is in front", clientKey(ex));
            }
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
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        try {
            sessions.close(cookie(ex, SESSION_COOKIE));
            clearSessionCookie(ex);
            json(ex, 200, "{\"ok\":true}");
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
                server.execute(() -> {
                    AlminExit.arm("a stop from the web panel");
                    server.halt(false);
                });
                return;
            }

            if ("restart".equals(action)) {
                if (!serverRunning) { json(ex, 409, err("The server is already stopped.")); return; }
                String cmd = cfg.webStartCommand == null ? "" : cfg.webStartCommand.trim();
                // Only this panel can bring it back; without supervisor mode the
                // JVM exits and whatever wrapper runs the server does it instead.
                boolean relaunch = cfg.webSupervisor && !cmd.isEmpty();
                restartAfterStop = relaunch;
                AlminLog.warn("[almin] web panel requested server RESTART (relaunch here: {})", relaunch);
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("action", "restart");
                o.addProperty("relaunch", relaunch);
                o.addProperty("message", relaunch
                    ? "Stopping, then running the start command."
                    : "Stopping. Whatever wrapper runs this server should start it again.");
                json(ex, 200, o.toString());
                server.execute(() -> {
                    if (!relaunch) AlminExit.arm("a restart from the web panel");
                    server.halt(false);
                });
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
        } catch (Throwable t) {
            fault(ex, t);
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
                // Output is inherited so the new server's log still lands
                // wherever this one's did. Input deliberately is not: this JVM
                // is about to halt, and a console reader left holding a
                // terminal nobody owns any more gets EIO on its first read —
                // which Minecraft logs as "Exception handling console input".
                // From /dev/null it reads EOF instead and the reader thread
                // ends quietly. Console typing is gone either way once this
                // process is; the panel's terminal is how you drive it now.
                pb.redirectInput(NULL_INPUT);
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
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
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    // ---------- routes: settings ----------

    /**
     * Settings the web panel may read but not write.
     *
     * <p>The password hash has its own route, which hashes rather than storing
     * what it is given. {@code web-start-command} is the one setting that turns
     * into a command on the host OS, so it stays where only someone at the
     * console or in game can set it — the same rule the in-game Web tab
     * follows.
     */
    private static final java.util.Set<String> WEB_LOCKED_KEYS = java.util.Set.of(
        "web-admin-password-hash",
        "web-start-command"
    );

    /** Settings whose new value only takes hold when the listener is rebuilt. */
    private static final java.util.Set<String> PANEL_RELOADS = java.util.Set.of(
        "web-ui-port", "web-ui-bind", "web-supervisor"
    );

    private void handleConfig(HttpExchange ex) throws IOException {
        try {
            if ("GET".equals(ex.getRequestMethod())) {
                if (!requireAuth(ex)) return;
                json(ex, 200, configJson());
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            if (!requireAuthSecure(ex)) return;

            JsonObject body = readBody(ex);
            String name = body.has("name") ? body.get("name").getAsString() : "";
            String raw = body.has("value") ? body.get("value").getAsString() : "";
            AlminConfig.Key key = AlminConfig.keyByName(name);
            if (key == null) { json(ex, 400, err("Unknown setting: " + name)); return; }
            if (WEB_LOCKED_KEYS.contains(key.name)) {
                json(ex, 403, err(key.name + " can't be changed from the web panel."));
                return;
            }
            Object parsed;
            try {
                parsed = key.parse(raw);
            } catch (IllegalArgumentException e) {
                json(ex, 400, err(key.name + ": " + e.getMessage()));
                return;
            }
            key.setter.accept(AlminConfig.get(), parsed);
            AlminConfig.save();
            AlminLog.info("[almin] web panel set config {} = {}", key.name, parsed);

            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("name", key.name);
            o.addProperty("value", String.valueOf(parsed));

            // Changing how the panel listens has to be answered before it is
            // acted on, or the reply never reaches the browser that asked.
            if (key.name.equals("web-ui-enabled") && Boolean.FALSE.equals(parsed)) {
                o.addProperty("panelStopping", true);
                json(ex, 200, o.toString());
                deferred(WebUi::stopNow);
                return;
            }
            if (PANEL_RELOADS.contains(key.name) && running()) {
                o.addProperty("panelRestarting", true);
                json(ex, 200, o.toString());
                deferred(WebUi::restartNow);
                return;
            }
            json(ex, 200, o.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private String configJson() {
        AlminConfig cfg = AlminConfig.get();
        JsonArray arr = new JsonArray();
        for (AlminConfig.Key k : AlminConfig.KEYS) {
            JsonObject o = new JsonObject();
            o.addProperty("name", k.name);
            o.addProperty("description", k.description);
            o.addProperty("type", k.type.name());
            o.addProperty("min", k.min);
            o.addProperty("max", k.max);
            o.addProperty("editable", !WEB_LOCKED_KEYS.contains(k.name));
            o.addProperty("reloadsPanel", PANEL_RELOADS.contains(k.name));
            // The hash is never shipped, even to a logged-in admin: it is a
            // password equivalent offline, and nothing here needs its value.
            o.addProperty("value", k.name.equals("web-admin-password-hash")
                ? (cfg.webAdminPasswordHash == null || cfg.webAdminPasswordHash.isBlank() ? "" : "(set)")
                : k.display(cfg));
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("keys", arr);
        root.addProperty("writableRoots", cfg.dirWritableRoots);
        return root.toString();
    }

    private void handleConfigReload(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            boolean ok = AlminConfig.reload();
            AlminLog.info("[almin] web panel reloaded config from disk ({})", ok ? "ok" : "not loaded");
            json(ex, ok ? 200 : 409, ok ? "{\"ok\":true}" : err("Config isn't loaded yet."));
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Sets the admin password.
     *
     * <p>Every other session is dropped, because the old password should not
     * outlive the change. This one is re-issued instead of being cut off — the
     * person who just changed it is the one who should stay in.
     */
    private void handlePassword(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String pw = body.has("password") ? body.get("password").getAsString() : "";
            if (pw.length() < 8) { json(ex, 400, err("Use at least 8 characters.")); return; }
            AlminConfig.get().webAdminPasswordHash = Passwords.hash(pw);
            AlminConfig.save();
            sessions.closeAll();
            String id = sessions.open(AlminConfig.get().webSessionMinutes);
            setSessionCookie(ex, id, behindTls(ex));
            AlminLog.info("[almin] web admin password changed from the panel by {}", clientKey(ex));
            json(ex, 200, "{\"ok\":true}");
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private void handleClearLog(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            boolean ok = AlminLog.clear();
            json(ex, ok ? 200 : 409, ok ? "{\"ok\":true}" : err("No log file open, or it could not be written."));
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    // ---------- routes: updates ----------

    private static final long UPDATE_CHECK_MS = 10_000;
    /** How long a version check is reused before GitHub is asked again. */
    private static final long UPDATE_CACHE_MS = 5 * 60_000;

    private volatile String updateJson = "";
    private volatile long updateJsonAt = 0L;

    private void handleUpdate(HttpExchange ex) throws IOException {
        try {
            if ("GET".equals(ex.getRequestMethod())) {
                if (!requireAuth(ex)) return;
                // Opening the Settings tab must not mean a call to GitHub and a
                // web thread parked for ten seconds every time.
                boolean force = "1".equals(queryParam(ex, "force"));
                String cached = updateJson;
                if (!force && !cached.isEmpty()
                        && System.currentTimeMillis() - updateJsonAt < UPDATE_CACHE_MS) {
                    json(ex, 200, cached);
                    return;
                }
                String fresh = updateStatusJson();
                updateJson = fresh;
                updateJsonAt = System.currentTimeMillis();
                json(ex, 200, fresh);
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            json(ex, 200, applyUpdateJson());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private String updateStatusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("current", UpdateChecker.currentVersion());
        o.addProperty("repo", AlminConfig.get().updateRepo);
        try {
            UpdateChecker.CheckResult r =
                UpdateChecker.checkAsync().get(UPDATE_CHECK_MS, TimeUnit.MILLISECONDS);
            switch (r) {
                case UpdateChecker.UpToDate ut -> {
                    o.addProperty("status", "current");
                    o.addProperty("latest", ut.version());
                }
                case UpdateChecker.UpdateAvailable ua -> {
                    o.addProperty("status", "available");
                    o.addProperty("latest", ua.release().version());
                    o.addProperty("hasJar", ua.release().hasJar());
                }
                case UpdateChecker.CheckFailed cf -> {
                    o.addProperty("status", "failed");
                    o.addProperty("reason", cf.reason());
                }
            }
        } catch (Exception e) {
            o.addProperty("status", "failed");
            o.addProperty("reason", "check timed out or failed: " + e.getClass().getSimpleName());
        }
        return o.toString();
    }

    /**
     * Downloads and installs a newer release, the same way {@code /almin update}
     * does. Runs inline rather than in the background so the browser gets a real
     * answer; the download is small and one web thread of four can wait.
     */
    private String applyUpdateJson() {
        JsonObject o = new JsonObject();
        UpdateChecker.CheckResult r;
        try {
            r = UpdateChecker.checkAsync().get(UPDATE_CHECK_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            o.addProperty("ok", false);
            o.addProperty("message", "Update check failed: " + e.getClass().getSimpleName());
            return o.toString();
        }
        if (r instanceof UpdateChecker.UpToDate ut) {
            o.addProperty("ok", true);
            o.addProperty("message", "Already on the latest version (" + ut.version() + ").");
            return o.toString();
        }
        if (r instanceof UpdateChecker.CheckFailed cf) {
            o.addProperty("ok", false);
            o.addProperty("message", "Update check failed: " + cf.reason());
            return o.toString();
        }
        UpdateChecker.Release rel = ((UpdateChecker.UpdateAvailable) r).release();
        if (!rel.hasJar()) {
            o.addProperty("ok", false);
            o.addProperty("message", "Release " + rel.version() + " has no .jar asset to download.");
            return o.toString();
        }
        Path dir = server.getServerDirectory();
        Path target = dir.resolve("mods").resolve(rel.jarName());
        AlminLog.info("[almin] web panel update: downloading {} -> mods/{}", rel.version(), rel.jarName());
        FileFetcher.FetchResult fetched = FileFetcher.fetch(rel.jarUrl(), target, dir);
        if (!fetched.ok()) {
            o.addProperty("ok", false);
            o.addProperty("message", "Download failed: " + fetched.message());
            return o.toString();
        }
        String removal = UpdateChecker.removeOldJar(target);
        AlminLog.info("[almin] web panel installed update {} ({} bytes) {}",
            rel.version(), fetched.bytes(), removal);
        updateJson = "";
        o.addProperty("ok", true);
        o.addProperty("message", "Installed " + rel.version() + ". " + removal
            + " Restart the server to run it.");
        return o.toString();
    }

    // ---------- routes: player activity ----------

    /** Rows sent to a browser in one response. The page scrolls; it doesn't need all of them. */
    private static final int ACTIVITY_ROWS = 500;

    /**
     * The activity log: what ordinary players did.
     *
     * <p>Behind the admin login like everything else here, which is the point —
     * the log deliberately excludes anyone who could read it, so it is never a
     * record of the people holding the password.
     */
    private void handleActivity(HttpExchange ex) throws IOException {
        try {
            if ("GET".equals(ex.getRequestMethod())) {
                if (!requireAuth(ex)) return;
                json(ex, 200, activityJson());
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            if (!requireAuthSecure(ex)) return;
            JsonObject body = readBody(ex);
            if (!"clear".equals(body.has("action") ? body.get("action").getAsString() : "")) {
                json(ex, 400, err("Unknown action."));
                return;
            }
            boolean ok = ActivityLog.clear();
            AlminLog.warn("[almin] web panel cleared the activity log ({})", ok ? "ok" : "file remained");
            json(ex, ok ? 200 : 500, ok ? "{\"ok\":true}"
                : err("Cleared in memory, but activity.log could not be deleted."));
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private String activityJson() {
        AlminConfig cfg = AlminConfig.get();
        JsonArray arr = new JsonArray();
        for (ActivityLog.Entry e : ActivityLog.recent(ACTIVITY_ROWS)) {
            JsonObject o = new JsonObject();
            o.addProperty("at", e.at());
            o.addProperty("player", e.player());
            o.addProperty("action", e.action());
            o.addProperty("detail", e.detail());
            o.addProperty("where", e.where());
            o.addProperty("dim", e.dim());
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("z", e.z());
            o.addProperty("count", e.count());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("rows", arr);
        root.addProperty("total", ActivityLog.size());
        root.addProperty("enabled", cfg.activityLog);
        root.addProperty("blocks", cfg.activityBlocks);
        root.addProperty("retentionMinutes", cfg.activityRetentionMinutes);
        return root.toString();
    }

    /**
     * One player's movements, and the things they did along the way.
     *
     * <p>Two series over the same clock: sampled positions from
     * {@link PlayerTracks}, and the rows from {@link ActivityLog} that carry a
     * place. Drawn together they are a path with markers on it.
     */
    private void handleTrack(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            String who = queryParam(ex, "player");
            JsonObject root = new JsonObject();

            JsonObject who2 = new JsonObject();
            for (Map.Entry<String, Integer> e : PlayerTracks.tracked().entrySet()) {
                who2.addProperty(e.getKey(), e.getValue());
            }
            root.add("players", who2);
            root.addProperty("trackSeconds", AlminConfig.get().activityTrackSeconds);

            if (who == null || who.isBlank()) {
                root.addProperty("player", "");
                root.add("points", new JsonArray());
                root.add("actions", new JsonArray());
                json(ex, 200, root.toString());
                return;
            }

            JsonArray points = new JsonArray();
            for (PlayerTracks.Point p : PlayerTracks.of(who)) {
                JsonObject o = new JsonObject();
                o.addProperty("at", p.at());
                o.addProperty("dim", p.dim());
                o.addProperty("x", p.x());
                o.addProperty("y", p.y());
                o.addProperty("z", p.z());
                points.add(o);
            }

            JsonArray actions = new JsonArray();
            for (ActivityLog.Entry e : ActivityLog.recent(ACTIVITY_ROWS)) {
                if (!e.player().equalsIgnoreCase(who)) continue;
                if (e.dim() == null || e.dim().isEmpty()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("at", e.at());
                o.addProperty("action", e.action());
                o.addProperty("detail", e.detail());
                o.addProperty("dim", e.dim());
                o.addProperty("x", e.x());
                o.addProperty("y", e.y());
                o.addProperty("z", e.z());
                o.addProperty("count", e.count());
                actions.add(o);
            }

            root.addProperty("player", who);
            root.add("points", points);
            root.add("actions", actions);
            json(ex, 200, root.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    // ---------- routes: players and masks ----------

    private void handlePlayers(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!requireServer(ex)) return;
            String body = onServer(this::playersJson, null);
            if (body == null) { json(ex, 503, err("The server didn't answer in time.")); return; }
            json(ex, 200, body);
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /** Runs on the server thread: the player list and the mask/history tables. */
    private String playersJson() {
        JsonObject root = new JsonObject();
        JsonArray online = new JsonArray();
        for (var p : server.getPlayerList().getPlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getGameProfile().name());
            o.addProperty("uuid", p.getUUID().toString());
            String mask = MaskConfig.maskFor(p.getUUID());
            o.addProperty("mask", mask == null ? "" : mask);
            o.addProperty("sessionMillis", PlayerHistory.sessionLength(p.getUUID()));
            online.add(o);
        }
        root.add("online", online);

        JsonArray history = new JsonArray();
        PlayerHistory hist = PlayerHistory.get(server);
        if (hist != null) {
            for (Map.Entry<java.util.UUID, PlayerHistory.Entry> e : hist.snapshot().entrySet()) {
                PlayerHistory.Entry v = e.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.getKey().toString());
                o.addProperty("name", v.name());
                o.addProperty("firstSeen", v.firstSeen());
                o.addProperty("lastSeen", v.lastSeen());
                o.addProperty("joins", v.joins());
                o.addProperty("playtimeMillis", v.playtimeMillis());
                String mask = MaskConfig.maskFor(e.getKey());
                o.addProperty("mask", mask == null ? "" : mask);
                history.add(o);
            }
        }
        root.add("history", history);
        root.addProperty("maxPlayers", server.getMaxPlayers());
        return root.toString();
    }

    private void handleMask(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String who = body.has("name") ? body.get("name").getAsString().trim() : "";
            String mask = body.has("mask") ? body.get("mask").getAsString().trim() : "";
            boolean clear = body.has("clear") && body.get("clear").getAsBoolean();
            if (who.isEmpty()) { json(ex, 400, err("Which player?")); return; }
            String out = onServer(() -> applyMask(who, mask, clear), null);
            if (out == null) { json(ex, 503, err("The server didn't answer in time.")); return; }
            json(ex, out.startsWith("{\"ok\":true") ? 200 : 400, out);
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /** Runs on the server thread: resolve the player, then set or clear. */
    private String applyMask(String who, String mask, boolean clear) {
        net.minecraft.server.players.NameAndId target = AlminUtil.resolveNameAndId(server, who);
        if (target == null) return err("Unknown player: " + who);
        if (clear) {
            boolean had = MaskConfig.clearMask(target.id());
            AlminUtil.refreshAllTabs(server);
            AlminLog.info("[almin] web panel cleared mask for {}", target.name());
            return "{\"ok\":true,\"message\":\"" + (had ? "Mask cleared." : "That player had no mask.") + "\"}";
        }
        if (mask.isEmpty()) return err("Mask cannot be empty.");
        MaskConfig.SetResult r = MaskConfig.setMask(target.id(), mask);
        if (r != MaskConfig.SetResult.OK) {
            return err("Could not set that mask (" + r + ").");
        }
        AlminUtil.refreshAllTabs(server);
        AlminLog.info("[almin] web panel set mask for {} -> '{}'", target.name(), mask);
        return "{\"ok\":true,\"message\":\"Mask set.\"}";
    }

    // ---------- routes: file transfer ----------

    /**
     * Streams an uploaded file into a writable root.
     *
     * <p>The same policy as every other write ({@link WebFiles#uploadTarget}),
     * settled before the first byte is read, and capped so a browser can't fill
     * the disk in one request.
     */
    private void handleFileUpload(HttpExchange ex) throws IOException {
        Path tmp = null;
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            String rel = queryParam(ex, "path");
            if (rel == null || rel.isBlank()) { json(ex, 400, err("No path given.")); return; }
            // Resolved here rather than on the server thread: it is path
            // arithmetic and the config, so the hop only added a way to time
            // out and blame the server for a rejected filename.
            WebFiles.Target t = WebFiles.uploadTarget(server, rel);
            if (!t.ok()) { json(ex, 403, err(t.problem())); return; }

            Path target = t.path();
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), ".almin-upload-", ".part");
            long written = 0;
            try (var in = ex.getRequestBody(); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    written += n;
                    if (written > WebFiles.MAX_UPLOAD_BYTES) {
                        json(ex, 413, err("File exceeds the "
                            + (WebFiles.MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB limit."));
                        return;
                    }
                    out.write(buf, 0, n);
                }
            }
            if (written == 0) { json(ex, 400, err("Empty upload.")); return; }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            AlminLog.info("[almin] web panel uploaded {} ({} bytes)", rel, written);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("path", rel);
            o.addProperty("bytes", written);
            json(ex, 200, o.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
            ex.close();
        }
    }

    /** Streams a file back to the browser, straight from disk. */
    private void handleFileDownload(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!requireServer(ex)) return;
            if (!"GET".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            String rel = queryParam(ex, "path");
            if (rel == null || rel.isBlank()) { json(ex, 400, err("No path given.")); return; }
            Path file = WebFiles.downloadable(server, rel);
            if (file == null) { json(ex, 404, err("No such file: " + rel)); return; }
            long size = Files.size(file);
            String name = file.getFileName().toString();
            var h = ex.getResponseHeaders();
            h.set("Content-Type", "application/octet-stream");
            h.set("Cache-Control", "no-store");
            h.set("X-Content-Type-Options", "nosniff");
            // Quoted and stripped of anything that could break out of the header.
            h.set("Content-Disposition", "attachment; filename=\""
                + name.replaceAll("[\"\\\\\\r\\n]", "_") + "\"");
            ex.sendResponseHeaders(200, size);
            try (var out = ex.getResponseBody()) {
                Files.copy(file, out);
            }
            AlminLog.info("[almin] web panel downloaded {} ({} bytes)", rel, size);
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Downloads a URL onto the server, the same as {@code /almin op fetch}.
     * The destination goes through the ordinary write rules.
     */
    private void handleFetch(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String url = body.has("url") ? body.get("url").getAsString().trim() : "";
            String dest = body.has("dest") ? body.get("dest").getAsString().trim() : "";
            if (url.isEmpty()) { json(ex, 400, err("No URL given.")); return; }
            if (dest.isEmpty()) { json(ex, 400, err("No destination given.")); return; }
            // A destination ending in / means "into this folder, keep the name".
            String rel = dest.endsWith("/")
                ? dest + FileFetcher.basenameFromUrl(url)
                : dest;
            WebFiles.Target t = WebFiles.uploadTarget(server, rel);
            if (!t.ok()) { json(ex, 403, err(t.problem())); return; }

            AlminLog.info("[almin] web panel fetching {} -> {}", url, rel);
            FileFetcher.FetchResult r = FileFetcher.fetch(url, t.path(), server.getServerDirectory());
            JsonObject o = new JsonObject();
            o.addProperty("ok", r.ok());
            o.addProperty("path", rel);
            o.addProperty("bytes", r.bytes());
            o.addProperty("message", r.ok()
                ? "Saved " + rel + " (" + r.bytes() + " bytes)."
                : r.message());
            json(ex, r.ok() ? 200 : 400, o.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Runs something that tears down this HTTP server, after the reply has had
     * time to leave. Called from a pool thread, it cannot stop its own pool.
     */
    private static void deferred(Runnable job) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) { return; }
            job.run();
        }, "Almin-web-reload");
        t.setDaemon(true);
        t.start();
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
     * Whether this request may use the admin tier.
     *
     * <p>A connection is <em>demonstrably</em> protected when the peer is
     * loopback (local, or a proxy on this machine) or a local proxy reports
     * HTTPS. Anything else is a plain HTTP connection from elsewhere, and
     * whether that is acceptable is the operator's call:
     * {@code web-require-secure} decides. It is off by default — switching it
     * on without a TLS proxy in front makes the panel impossible to log into
     * from another machine, which is not a useful way to fail.
     */
    private static boolean secure(HttpExchange ex) {
        if (isProtected(ex)) return true;
        return !AlminConfig.get().webRequireSecure;
    }

    /** True when the transport really is protected, rather than merely permitted. */
    private static boolean isProtected(HttpExchange ex) {
        return loopback(ex) || behindTls(ex);
    }

    private static boolean loopback(HttpExchange ex) {
        InetSocketAddress remote = ex.getRemoteAddress();
        InetAddress a = remote == null ? null : remote.getAddress();
        return a != null && a.isLoopbackAddress();
    }

    /** Whether the external leg is HTTPS, per the proxy — only believed from loopback. */
    private static boolean behindTls(HttpExchange ex) {
        return loopback(ex) && "https".equalsIgnoreCase(firstHeader(ex, "X-Forwarded-Proto"));
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
        if (loopback(ex) && xff != null && !xff.isBlank()) {
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

}
