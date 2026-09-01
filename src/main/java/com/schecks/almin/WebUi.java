package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Returned by {@link #tryPreferred} for a failure that has already been
     * reported and that no other port would fix. Identity-compared, never
     * thrown.
     */
    private static final IOException REPORTED = new IOException("already reported");

    /** How long a panel with a failed restart on it waits to be read. */
    private static final long REPORT_WINDOW_MS = 60 * 60 * 1000L;

    /** A website Stop leaves one temporary route back to the Start button. */
    private static volatile boolean keepPanelForStart = false;
    private static final AtomicBoolean HOLDING_FOR_START = new AtomicBoolean();

    /** Attempts on the configured port before looking elsewhere, and the wait between. */
    private static final int PREFERRED_RETRIES = 4;
    private static final long PREFERRED_RETRY_MS = 750;

    private final HttpServer http;
    /**
     * Kept because {@link HttpServer#stop} deliberately does not shut down an
     * executor it was given. Without this, every panel restart left four
     * threads behind.
     */
    private final ExecutorService pool;
    /**
     * Lazily created for BlueMap's tile streams. A BlueMap page opens many
     * files at once and holds one SSE request open; making those occupy the
     * panel's four control workers would let the map starve the terminal and
     * every other admin route. It stays absent when BlueMap is not used.
     */
    private volatile ExecutorService blueMapPool;
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
     * Why the last attempt to start the server again failed, or "" if none
     * has. Shown in the panel: a restart that could not restart is exactly the
     * failure an owner needs told about, and it is the one that used to be
     * silent.
     */
    private static volatile String relaunchError = "";

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
        keepPanelForStart = false;
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
     * Binds and starts serving on the configured port.
     *
     * <p>The public address is normally a proxy, tunnel, firewall rule or
     * bookmark aimed at this exact port. Moving to a nearby port is therefore
     * not a recovery: it creates a listener nobody can reach and then falsely
     * announces that the website is up. A proven leftover Almin process is
     * still cleared below, but an unrelated owner of the configured port is a
     * real startup failure and is reported as one.
     */
    private static void listen(MinecraftServer server, AlminConfig cfg) {
        hookShutdown();
        String bind = (cfg.webUiBind == null || cfg.webUiBind.isBlank()) ? "0.0.0.0" : cfg.webUiBind.trim();
        boolean firstRun = cfg.webUiPort <= 0;
        int wanted = firstRun ? 8100 + new java.security.SecureRandom().nextInt(900) : cfg.webUiPort;

        // A predecessor that has only just died can still hold the socket for a
        // moment. Waiting beats moving: the configured port is the address
        // people have bookmarked.
        IOException first = tryPreferred(server, cfg, bind, wanted, firstRun);
        if (first == null || first == REPORTED) return;

        // Still taken, and waiting did not help. That is the signature of a
        // leftover Almin JVM squatting the port with nothing behind it — it is
        // never going to let go on its own. If this is provably ours, end it
        // and take the address back rather than drifting to a new one.
        if (WebLock.clearStale(dirOf(server), wanted)) {
            IOException again = tryPreferred(server, cfg, bind, wanted, firstRun);
            if (again == null || again == REPORTED) return;
            first = again;
        }

        fail("could not bind the configured address " + bind + ":" + wanted
            + "; the website was NOT moved to another port because its proxy/tunnel "
            + "would still point here", first == null ? "address already in use" : first.getMessage());
    }

    /**
     * Tries the port everybody has bookmarked, a few times.
     *
     * <p>Returns null once it is listening, or the {@link java.net.BindException}
     * that says the port is held by someone else. Any other failure is terminal
     * and is reported here rather than returned — there is nothing a different
     * port would fix.
     */
    private static IOException tryPreferred(MinecraftServer server, AlminConfig cfg,
                                            String bind, int wanted, boolean firstRun) {
        IOException first = null;
        for (int attempt = 0; attempt < PREFERRED_RETRIES; attempt++) {
            try {
                bindOnOwnThread(server, cfg, bind, wanted);
                if (firstRun) {
                    cfg.webUiPort = wanted;      // remember the first-run pick
                    AlminConfig.save();
                }
                return null;
            } catch (java.net.BindException e) {
                if (first == null) first = e;
                if (attempt < PREFERRED_RETRIES - 1) sleep(PREFERRED_RETRY_MS);
            } catch (IOException e) {
                fail("could not bind " + bind + ":" + wanted, e.getMessage());
                return REPORTED;
            } catch (RuntimeException e) {
                fail("failed to start", e.toString());
                return REPORTED;
            }
        }
        return first;
    }

    /**
     * Where the server lives, or null if there isn't one. The panel outlives
     * the server on purpose in places, and the port bookkeeping has to keep
     * working across that rather than reaching through a dead reference.
     */
    private static Path dirOf(MinecraftServer server) {
        return server == null ? null : server.getServerDirectory();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        http.createContext("/api/file/mkdir", guard("/api/file/mkdir", ui::handleFileMkdir));
        http.createContext("/api/server", guard("/api/server", ui::handleServerControl));
        http.createContext("/api/mods", guard("/api/mods", ui::handleMods));
        http.createContext("/api/mods/save", guard("/api/mods/save", ui::handleModSave));
        http.createContext("/api/mods/delete", guard("/api/mods/delete", ui::handleModDelete));
        http.createContext("/api/mods/files", guard("/api/mods/files", ui::handleModFiles));
        http.createContext("/api/mods/upload", guard("/api/mods/upload", ui::handleModUpload));
        http.createContext("/api/mods/files/delete", guard("/api/mods/files/delete", ui::handleModFileDelete));
        http.createContext("/api/mods/modrinth", guard("/api/mods/modrinth", ui::handleModrinth));
        http.createContext("/api/mods/icon", guard("/api/mods/icon", ui::handleModIcon));
        http.createContext("/api/config", guard("/api/config", ui::handleConfig));
        http.createContext("/api/config/reload", guard("/api/config/reload", ui::handleConfigReload));
        http.createContext("/api/password", guard("/api/password", ui::handlePassword));
        http.createContext("/api/update", guard("/api/update", ui::handleUpdate));
        http.createContext("/api/clearlog", guard("/api/clearlog", ui::handleClearLog));
        http.createContext("/api/reset", guard("/api/reset", ui::handleReset));
        http.createContext("/api/players", guard("/api/players", ui::handlePlayers));
        http.createContext("/api/mask", guard("/api/mask", ui::handleMask));
        http.createContext("/api/file/upload", guard("/api/file/upload", ui::handleFileUpload));
        http.createContext("/api/file/download", guard("/api/file/download", ui::handleFileDownload));
        http.createContext("/api/fetch", guard("/api/fetch", ui::handleFetch));
        http.createContext("/api/activity", guard("/api/activity", ui::handleActivity));
        http.createContext("/api/track", guard("/api/track", ui::handleTrack));
        http.createContext("/api/map", guard("/api/map", ui::handleMap));
        http.createContext("/api/bluemap", guard("/api/bluemap", ui::handleBlueMap));
        http.createContext("/bluemap", guard("/bluemap", ui::handleBlueMapProxy));
        http.createContext("/api/scene/context",
            guard("/api/scene/context", ui::handleSceneContext));
        http.createContext("/api/head", guard("/api/head", ui::handleHead));
        http.createContext("/api/insights", guard("/api/insights", ui::handleInsights));
        http.createContext("/api/insights/find", guard("/api/insights/find", ui::handleFind));
        http.createContext("/api/ai/key", guard("/api/ai/key", ui::handleAiKey));
        http.createContext("/api/ai/diagnostics",
            guard("/api/ai/diagnostics", ui::handleAiDiagnostics));
        http.createContext("/api/client/review", guard("/api/client/review", ui::handleModReview));
        http.createContext("/api/servermods", guard("/api/servermods", ui::handleServerMods));
        http.createContext("/api/servermods/upload",
            guard("/api/servermods/upload", ui::handleServerModUpload));
        http.createContext("/api/servermods/change",
            guard("/api/servermods/change", ui::handleServerModChange));
        http.createContext("/api/properties", guard("/api/properties", ui::handleProperties));
        http.createContext("/api/blocks", guard("/api/blocks", ui::handleBlocks));
        http.createContext("/api/block", guard("/api/block", ui::handleBlock));
        http.createContext("/api/item", guard("/api/item", ui::handleItem));
        http.createContext("/api/client", guard("/api/client", ui::handleClient));
        // In supervisor mode the web threads must be non-daemon, or the JVM
        // exits the moment the server thread ends and takes the panel with it.
        http.setExecutor(pool);
        http.start();
        ui.serverRunning = serverUp;

        // A bound socket is not yet a website. Exercise the real root handler
        // over loopback before publishing this instance or printing the success
        // line. This catches page initialisation faults, dead worker pools and
        // listeners that accept a connection but never answer it.
        try {
            verifyLocalRoot(bind, port);
        } catch (IOException e) {
            try { http.stop(0); } catch (RuntimeException ignored) { }
            pool.shutdownNow();
            throw new IOException("listener bound, but GET / did not work: " + e.getMessage(), e);
        }

        instance = ui;
        lastError = "";
        try {
            if (!serverUp) {
                ui.publicJson = stoppedJson();
                ui.fullJson = stoppedJson();
            }
            ui.rebuild();
            ui.writeCaddyfile(cfg);
            // Leave a note saying this process holds the port, so the next
            // start can tell a leftover of ours from someone else's server.
            WebLock.write(dirOf(server), bind, port);
        } catch (RuntimeException e) {
            // The panel is already listening. A failed first snapshot or an
            // unwritable Caddyfile is not a reason to tear it back down.
            AlminLog.warn("[almin] web panel is up, but post-start setup failed: {}", e.toString());
        }
        boolean pw = cfg.webAdminPasswordHash != null && !cfg.webAdminPasswordHash.isBlank();
        AlminLog.info("[almin] web panel verified with GET / on http://{}:{} "
                + "(public metrics {}, admin login {})",
            bind, port, cfg.webPublicMetrics ? "on" : "off",
            pw ? "ready" : "NO PASSWORD SET — run /almin op web password <pw>");
        CONSOLE.info("[almin] web panel verified locally on {}  ({})", browsableUrl(),
            pw ? "log in with your admin password"
               : "no password set yet — /almin op web password <pw>");
    }

    /**
     * Makes a complete HTTP request to the listener we just started.
     *
     * <p>A raw socket deliberately avoids system HTTP proxy settings. Reading
     * through EOF also proves the full embedded page can be written, rather
     * than accepting a TCP connection and failing halfway through the body.
     */
    private static void verifyLocalRoot(String bind, int port) throws IOException {
        String host;
        try {
            InetAddress address = InetAddress.getByName(bind);
            if (address.isAnyLocalAddress()) {
                host = bind != null && bind.contains(":") ? "::1" : "127.0.0.1";
            } else {
                host = address.getHostAddress();
            }
        } catch (RuntimeException e) {
            host = "127.0.0.1";
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((
                "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] response;
            try (InputStream in = socket.getInputStream()) {
                response = in.readAllBytes();
            }
            String text = new String(response, StandardCharsets.UTF_8);
            int lineEnd = text.indexOf("\r\n");
            String status = lineEnd < 0 ? text : text.substring(0, lineEnd);
            if (!status.contains(" 200 ")) throw new IOException("root answered " + status);
            if (!text.contains("<title>Almin</title>")) {
                throw new IOException("root returned an incomplete or unexpected page");
            }
        }
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
        ExecutorService blue = ui.blueMapPool;
        if (blue != null) blue.shutdownNow();
        // The port is ours again to give up; the note about holding it goes too.
        try {
            WebLock.clear(dirOf(ui.server));
        } catch (RuntimeException ignored) {
            // Shutting down; a leftover note is only a tidy-up next start.
        }
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

    private synchronized ExecutorService blueMapPool() {
        if (blueMapPool == null || blueMapPool.isShutdown()) {
            boolean supervisor = AlminConfig.get().webSupervisor;
            blueMapPool = Executors.newFixedThreadPool(16, r -> {
                Thread t = new Thread(r, "Almin-BlueMap");
                t.setDaemon(!supervisor);
                return t;
            });
        }
        return blueMapPool;
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
     * Called when the Minecraft server has stopped.
     *
     * <p>This only records what happened and decides whether the panel stays
     * up. Starting the server again is {@link #handOver()}, which runs last of
     * everything at shutdown — it ends the process, so nothing that still has
     * something to write may come after it.
     */
    public static void onServerStopped() {
        // Recorded even with no panel up: one started later must not think it
        // still has a live server behind it.
        serverUp = false;
        WebUi ui = instance;
        if (ui == null) return;
        ui.serverRunning = false;
        ui.publicJson = stoppedJson();
        ui.fullJson = stoppedJson();
        // A restart needs the panel for the handover, and needs it afterwards
        // too if the handover fails — that is the only way anyone would find
        // out that it did.
        if (ServerRelaunch.armed()) return;
        if (keepPanelForStart) {
            AlminLog.info("[almin] server stopped from the website — keeping its Start button "
                + "available for {} minutes", REPORT_WINDOW_MS / 60_000);
            holdOpenForStart();
            return;
        }
        if (!AlminConfig.get().webSupervisor) {
            stop();
            return;
        }
        AlminLog.info("[almin] server stopped — web panel still up (supervisor mode)");
    }

    /**
     * The second half of a restart: starts the server again and gets out of
     * its way.
     *
     * <p>Runs at the very end of shutdown, because it ends this process. The
     * order is the one that survives a mistake: the new server is started
     * <em>first</em>, and only once it is genuinely on its way does this one
     * give up its port and exit. If the launch fails there is no handover at
     * all — the panel stays up saying why, which beats exiting into a server
     * that is simply down with nothing left to bring it back.
     */
    public static void handOver() {
        if (!ServerRelaunch.armed()) return;
        try {
            handOverOrStayUp();
        } catch (Throwable t) {
            // Whatever went wrong, this process must not be left half-restarted
            // and immortal. Fall back to what a restart used to be.
            AlminLog.warn("[almin] handover failed: {}", t.toString());
            CONSOLE.warn("[almin] Handing the server over failed", t);
            AlminExit.arm("a failed restart");
        }
    }

    private static void handOverOrStayUp() {
        String why = ServerRelaunch.why();
        boolean hadPanel = instance != null;
        // The replacement uses the same web address. Release it before the
        // process is created, otherwise a fast server can reach its bind while
        // this old panel still owns the port.
        if (hadPanel) stop();
        ServerRelaunch.Result r = ServerRelaunch.launch(dirOf(boundServer));
        if (!r.ok()) {
            relaunchError = r.message();
            lastError = r.message();
            ServerRelaunch.disarm();
            if (hadPanel) {
                listen(boundServer, AlminConfig.get());
                CONSOLE.warn("[almin] {} could not start the server again: {} — leaving the panel "
                    + "up so you can start it from there.", why, r.message());
                AlminLog.warn("[almin] relaunch after {} failed; panel stays up", why);
                holdOpenForStart();
                return;
            }
            // Nothing to fall back on. Behave as before: exit, and let whatever
            // is outside make of it what it will.
            AlminExit.arm(why);
            return;
        }

        AlminLog.info("[almin] {} — new server launched; handing over the port and exiting", why);
        AlminLog.close();
        Runtime.getRuntime().halt(0);
    }

    /**
     * Keeps the process alive long enough for someone to be told a restart
     * failed, then lets it go.
     *
     * <p>Without supervisor mode every web thread is a daemon, so once the
     * server thread ends the JVM exits and takes the panel — and the only
     * account of what went wrong — with it. This is the one case where that is
     * the wrong outcome: the server is down and nothing else is going to bring
     * it back.
     *
     * <p>It is deliberately not forever. A process squatting a port with
     * nothing behind it is the bug this whole area exists to fix, so the wait
     * has an end.
     */
    private static void holdOpenForStart() {
        if (!HOLDING_FOR_START.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + REPORT_WINDOW_MS;
                while (System.currentTimeMillis() < deadline && instance != null) {
                    sleep(5000);
                }
                if (instance == null) return;  // Start was pressed, or the panel was stopped
                CONSOLE.warn("[almin] Nobody started the server in the last {} minutes. Closing "
                    + "the temporary launcher panel.", REPORT_WINDOW_MS / 60_000);
                AlminLog.close();
                stop();
                Runtime.getRuntime().halt(0);
            } finally {
                HOLDING_FOR_START.set(false);
            }
        }, "Almin-web-report");
        // Not a daemon: holding the JVM open is the entire job.
        t.setDaemon(false);
        t.start();
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
            // The page ships inside the mod jar, so an update changes it. A
            // browser holding a cached copy would keep showing the old panel
            // against a new server for as long as it felt like it.
            ex.getResponseHeaders().set("Cache-Control", "no-store, must-revalidate");
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
            // The version this page was served by. A tab left open across an
            // update is looking at the old panel; when this changes under it,
            // it reloads itself onto the new one.
            o.addProperty("version", UpdateChecker.currentVersion());
            ServerRelaunch.Plan plan = ServerRelaunch.plan();
            o.addProperty("canStart", plan.ok());
            o.addProperty("restarting", ServerRelaunch.armed());
            // The start command is this server's own java invocation: install
            // paths, heap size, jar names. That is a description of the host,
            // and this route answers before anyone has logged in.
            if (authed(ex)) {
                o.addProperty("canRelaunch", ServerRelaunch.enabled() && plan.ok());
                o.addProperty("startCommand", plan.ok() ? plan.display() : "");
                if (!plan.ok()) o.addProperty("startProblem", plan.problem());
                if (!relaunchError.isEmpty()) o.addProperty("relaunchError", relaunchError);
                // Whether to ask for faces at all. Without this the panel would
                // request one per row and take a 404 each time on a server that
                // has them turned off.
                o.addProperty("heads", cfg.webPlayerHeads);
            }
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
     * Stops, restarts or starts the Minecraft server.
     *
     * <p><b>Stop</b> is the same graceful halt as {@code /stop}, and it means
     * stop: nothing starts it again.
     *
     * <p><b>Restart</b> genuinely restarts. Almin stops the server and then
     * starts it again itself, from this process, so it does not depend on a
     * wrapper script or a host panel noticing the exit. On a server where
     * nothing was watching for that exit, Restart used to be a Stop with a
     * different label.
     *
     * <p><b>Start</b> exists for a server that is down while the panel is
     * still up. It cannot boot a server inside this JVM — Minecraft's bootstrap
     * is one-shot — so it launches a fresh process and hands the port over by
     * ending this one.
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
                // Stop means stop. Anything left armed from an earlier request
                // would turn this into a restart.
                ServerRelaunch.disarm();
                // A button that stops the website can never offer Start. Keep
                // this one panel alive temporarily even when permanent
                // supervisor mode is off.
                keepPanelForStart = true;
                json(ex, 200, "{\"ok\":true,\"action\":\"stop\"}");
                // Halt on the server thread, after the response is on its way.
                server.execute(() -> server.halt(false));
                return;
            }

            if ("restart".equals(action)) {
                if (!serverRunning) { json(ex, 409, err("The server is already stopped.")); return; }
                boolean relaunch = ServerRelaunch.arm("a restart from the web panel");
                AlminLog.warn("[almin] web panel requested server RESTART (started again from here: {})",
                    relaunch);
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("action", "restart");
                o.addProperty("relaunch", relaunch);
                o.addProperty("restarting", true);
                o.addProperty("message", relaunch
                    ? "Stopping, then starting it again from here. This page reconnects on its own."
                    : "Stopping. Whatever wrapper runs this server should start it again.");
                json(ex, 200, o.toString());
                server.execute(() -> {
                    if (!relaunch) AlminExit.arm("a restart from the web panel");
                    server.halt(false);
                });
                return;
            }

            if ("start".equals(action)) {
                if (serverRunning) { json(ex, 409, err("The server is already running.")); return; }
                ServerRelaunch.Plan plan = ServerRelaunch.plan();
                if (!plan.ok()) { json(ex, 409, err(plan.problem() + ".")); return; }
                AlminLog.warn("[almin] web panel requested server START via {}: {}",
                    plan.source(), plan.display());
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("action", "start");
                o.addProperty("restarting", true);
                o.addProperty("message", "Starting the server. This page reconnects on its own.");
                json(ex, 200, o.toString());
                handOffNow();
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
     * Starts the server and then gets out of the way, so the new process can
     * take the web port over.
     *
     * <p>Runs off the HTTP thread, after a beat, so the answer is already in
     * the browser before this process stops being able to send one. If the
     * launch fails nothing is handed over and the panel stays exactly as it
     * was, with the reason on it.
     */
    private void handOffNow() {
        Thread t = new Thread(() -> {
            sleep(400);                                // let the response reach the browser
            MinecraftServer stopped = server;
            stop();                                    // replacement needs this exact port
            ServerRelaunch.Result r = ServerRelaunch.launch(dirOf(stopped));
            if (!r.ok()) {
                relaunchError = r.message();
                lastError = r.message();
                listen(stopped, AlminConfig.get());
                holdOpenForStart();
                CONSOLE.warn("[almin] Start failed: {}", r.message());
                return;                                // panel stays up, and says so
            }
            AlminLog.info("[almin] server launched from the panel; handing over and exiting");
            AlminLog.close();
            Runtime.getRuntime().halt(0);
        }, "Almin-web-handoff");
        // Not a daemon: this thread is the only thing left that has to finish.
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
                je.addProperty("modified", e.modified());
                je.addProperty("items", e.items());
                je.addProperty("writable", e.writable());
                je.addProperty("deletable", e.deletable());
                arr.add(je);
            }
            o.add("entries", arr);
            o.addProperty("writable", listing.writable());
            o.addProperty("deletable", listing.deletable());
            o.addProperty("roots", AlminConfig.get().dirWritableRoots);
            o.addProperty("deleteRoots", AlminConfig.get().dirDeletableRoots);
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
            AlminLog.info("[almin] web delete {} ({})", rel, r.message());
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

    /**
     * Makes one folder inside the folder being looked at.
     *
     * <p>The write rules are the same as everywhere else and live in
     * {@link WebFiles#mkdir} — this is only the transport.
     */
    private void handleFileMkdir(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String parent = body.has("path") ? body.get("path").getAsString() : "";
            String name = body.has("name") ? body.get("name").getAsString().trim() : "";
            WebFiles.Result r = onServer(() -> WebFiles.mkdir(server, parent, name),
                WebFiles.Result.fail("The server didn't answer in time."));
            if (r.ok()) AlminLog.info("[almin] web created folder {}/{}", parent, name);
            json(ex, r.ok() ? 200 : 400, result(r));
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    // ---------- routes: mods on this server ----------

    /**
     * The jars in this server's own {@code mods/} folder.
     *
     * <p>Separate from {@code /api/mods}, which is the list of suggestions
     * sent to players. The two used to share a tab and a heading, and "add a
     * mod" meant two different acts depending on which half of the page you
     * were looking at.
     */
    private void handleServerMods(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!requireServer(ex)) return;
            List<ServerMods.Installed> list = onServer(() -> ServerMods.list(server), List.of());
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            for (ServerMods.Installed m : list) {
                JsonObject j = new JsonObject();
                j.addProperty("file", m.file());
                j.addProperty("id", m.modId());
                j.addProperty("name", m.name());
                j.addProperty("version", m.version());
                j.addProperty("bytes", m.bytes());
                j.addProperty("modified", m.modified());
                j.addProperty("loaded", m.loaded());
                j.addProperty("enabled", m.enabled());
                j.addProperty("ours", m.ours());
                arr.add(j);
            }
            o.add("mods", arr);
            o.addProperty("maxBytes", ServerMods.MAX_BYTES);
            o.addProperty("folder", "mods/");
            json(ex, 200, o.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Puts a jar into {@code mods/}.
     *
     * <p>The same guards the offer upload uses — a bare {@code .jar} name, a
     * capped body, and a file that has to really be a Fabric mod before it is
     * kept — and it is written to a temporary file first, so nothing
     * half-received is ever visible in the folder Fabric reads.
     */
    private void handleServerModUpload(HttpExchange ex) throws IOException {
        Path tmp = null;
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }

            String name = queryParam(ex, "name");
            Path dir = onServer(() -> ServerMods.dir(server), null);
            if (dir == null) { json(ex, 409, err("This server has no mods/ folder.")); return; }
            if (onServer(() -> ServerMods.resolve(server, name), null) == null) {
                json(ex, 400, err("Filename must be a plain .jar name, e.g. sodium-0.5.11.jar"));
                return;
            }
            boolean replace = "1".equals(queryParam(ex, "replace"));

            long max = ServerMods.MAX_BYTES;
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
            Path staged = tmp;
            ServerMods.Result r =
                onServer(() -> ServerMods.install(server, staged, name, replace),
                    ServerMods.Result.fail("The server didn't answer in time."));
            if (r.ok()) tmp = null;
            JsonObject o = new JsonObject();
            o.addProperty("ok", r.ok());
            o.addProperty("message", r.message());
            o.addProperty("bytes", written);
            json(ex, r.ok() ? 200 : 400, o.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
            ex.close();
        }
    }

    /** Turns a server mod on or off, or deletes it. */
    private void handleServerModChange(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!requireServer(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject b = readBody(ex);
            String file = b.has("file") ? b.get("file").getAsString() : "";
            String what = b.has("action") ? b.get("action").getAsString() : "";
            ServerMods.Result r = switch (what) {
                case "enable" -> onServer(() -> ServerMods.setEnabled(server, file, true),
                    ServerMods.Result.fail("The server didn't answer in time."));
                case "disable" -> onServer(() -> ServerMods.setEnabled(server, file, false),
                    ServerMods.Result.fail("The server didn't answer in time."));
                case "delete" -> onServer(() -> ServerMods.delete(server, file),
                    ServerMods.Result.fail("The server didn't answer in time."));
                default -> ServerMods.Result.fail("Unknown action.");
            };
            JsonObject o = new JsonObject();
            o.addProperty("ok", r.ok());
            o.addProperty("message", r.message());
            json(ex, r.ok() ? 200 : 400, o.toString());
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
                j.addProperty("kind", m.kind());
                j.addProperty("source", m.sourceOrEmpty());
                j.addProperty("page", m.pageOrEmpty());
                j.addProperty("icon", ModIcons.exists(m));
                arr.add(j);
            }
            o.add("mods", arr);
            o.addProperty("advertise", cfg.modsAdvertise);
            o.addProperty("denyKicks", cfg.modsDenyKicks);
            o.addProperty("requireClientMod", cfg.requireClientMod);
            o.addProperty("restricted", cfg.modsRestricted);
            o.addProperty("showRestricted", cfg.modsShowRestricted);
            o.addProperty("restrictedKick", cfg.modsRestrictedKick);
            // Jars sitting in modfiles/ that nothing advertises. Normally
            // empty: an upload now makes its own advertisement. What lands
            // here is a leftover from before that, or from a removed offer.
            JsonArray unused = new JsonArray();
            java.util.Set<String> claimed = new java.util.HashSet<>();
            for (ModOffers.AdvertisedMod m : ModOffers.list()) {
                if (m.serverHosted()) claimed.add(m.file());
            }
            for (String f : ModOffers.availableFiles()) {
                if (!claimed.contains(f)) unused.add(f);
            }
            o.add("unusedFiles", unused);
            o.addProperty("maxOffers", ModOffers.MAX_OFFERS);
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
                b.has("file") ? b.get("file").getAsString().trim() : "",
                pageFor(b, id),
                sourceFor(b, id, file));
            // If the server holds the jar, believe the jar about its own id.
            mod = ModOffers.correctFromJar(mod);
            ModOffers.AddResult r = ModOffers.add(mod);
            AlminLog.info("[almin] web set mod offer {} -> {} ({})", mod.modId(), url, r);
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

            // An uploaded jar advertises itself. Before this, uploading put a
            // file in a folder and left a second, separate step to do — which
            // is why the panel had two lists that were nearly the same thing.
            // The jar already says what it is, so there is nothing to ask.
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("name", name);
            o.addProperty("bytes", written);
            ModJars.Meta meta = ModJars.read(target);
            if (meta.ok()) {
                ModOffers.AdvertisedMod was = existingOffer(meta.modId());
                ModOffers.AdvertisedMod mod = new ModOffers.AdvertisedMod(
                    meta.modId(), meta.name(), meta.version(),
                    "", sha256Of(target),
                    was != null && was.required(),
                    name,
                    was == null ? "" : was.pageOrEmpty(),
                    was == null ? "upload" : was.sourceOrEmpty());
                ModOffers.AddResult r = ModOffers.add(mod);
                o.addProperty("advertised", r == ModOffers.AddResult.OK);
                o.addProperty("modId", mod.modId());
                o.addProperty("modName", mod.name());
                if (r != ModOffers.AddResult.OK) o.addProperty("listProblem", r.toString());
            } else {
                // looksLikeValidMod already passed, so this is a jar with a
                // fabric.mod.json that has no id in it. Rare, and worth saying.
                o.addProperty("advertised", false);
                o.addProperty("listProblem", "NO_ID");
            }
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

    /**
     * The picture for one advertised mod.
     *
     * <p>Served from this origin rather than linked to Modrinth's CDN, so that
     * opening the Mods tab does not make the admin's browser announce to a
     * third party what this server runs — and so the tab still has pictures on
     * a machine whose browser cannot reach the internet.
     */
    private void handleModIcon(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            String id = queryParam(ex, "id");
            ModOffers.AdvertisedMod mod = ModOffers.list().stream()
                .filter(m -> m.modId().equalsIgnoreCase(id))
                .findFirst().orElse(null);
            ModIcons.Icon icon = mod == null ? ModIcons.cached(id) : ModIcons.forMod(mod);
            if (icon == null) { json(ex, 404, err("No icon for " + id + ".")); return; }
            image(ex, icon.contentType(), icon.bytes());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /** The project page a saved mod should link to, preserving what it had. */
    private static String pageFor(JsonObject b, String id) {
        if (b.has("page")) return b.get("page").getAsString().trim();
        ModOffers.AdvertisedMod existing = existingOffer(id);
        return existing == null ? "" : existing.pageOrEmpty();
    }

    /**
     * How this mod was added. An edit through the panel must not relabel a
     * Modrinth mod as hand-typed just because the form posted every field, so
     * an existing offer keeps whatever it already said.
     */
    private static String sourceFor(JsonObject b, String id, String file) {
        if (b.has("source")) return b.get("source").getAsString().trim();
        ModOffers.AdvertisedMod existing = existingOffer(id);
        if (existing != null && !existing.sourceOrEmpty().isEmpty()) return existing.sourceOrEmpty();
        return file.isEmpty() ? "link" : "upload";
    }

    private static ModOffers.AdvertisedMod existingOffer(String id) {
        return ModOffers.list().stream()
            .filter(m -> m.modId().equalsIgnoreCase(id))
            .findFirst().orElse(null);
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
        root.addProperty("deletableRoots", cfg.dirDeletableRoots);
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

    /**
     * Throws away the records the panel draws, one kind at a time.
     *
     * <p>Three separate switches rather than one button, because they answer
     * different questions: the rows are what people did, the paths are where
     * they walked, and the pictures are what the ground looked like. Wanting
     * a clean map is not the same as wanting to forget the afternoon.
     */
    private void handleReset(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject b = readBody(ex);
            boolean actions = b.has("actions") && b.get("actions").getAsBoolean();
            boolean paths = b.has("paths") && b.get("paths").getAsBoolean();
            boolean pictures = b.has("pictures") && b.get("pictures").getAsBoolean();
            if (!actions && !paths && !pictures) {
                json(ex, 400, err("Nothing was selected."));
                return;
            }
            WorldReset.Cleared done = onServer(() -> WorldReset.wipe(actions, paths, pictures),
                new WorldReset.Cleared(false, false, false, "The server didn't answer in time."));
            AlminLog.info("[almin] web reset: {} (by {})", done.message(), clientKey(ex));
            JsonObject o = new JsonObject();
            o.addProperty("ok", done.actions() || done.paths() || done.pictures());
            o.addProperty("message", "Done \u2014 " + done.message() + ".");
            json(ex, 200, o.toString());
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
            JsonObject body = readBody(ex);
            // A new jar on disk changes nothing until the server runs it, so
            // restarting is the default rather than an afterthought. Pass
            // {"restart": false} to install now and apply it later.
            boolean restart = !body.has("restart") || body.get("restart").getAsBoolean();
            json(ex, 200, applyUpdateJson(restart));
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
        String queued = ServerAutoUpdater.pendingVersion();
        if (!queued.isEmpty()) o.addProperty("queued", queued);
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
    private String applyUpdateJson(boolean restart) {
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
        Path staged;
        try {
            staged = ServerJarUpdate.stage(dir, rel.jarName());
        } catch (RuntimeException e) {
            o.addProperty("ok", false);
            o.addProperty("message", "Release has an invalid jar name.");
            return o.toString();
        }
        ServerJarUpdate.discard(staged);
        AlminLog.info("[almin] web panel update: staging {} -> mods/{}",
            rel.version(), staged.getFileName());
        FileFetcher.FetchResult fetched = FileFetcher.fetch(rel.jarUrl(), staged, dir);
        if (!fetched.ok()) {
            ServerJarUpdate.discard(staged);
            o.addProperty("ok", false);
            o.addProperty("message", "Download failed: " + fetched.message());
            return o.toString();
        }
        if (!UpdateChecker.looksLikeValidMod(staged)) {
            ServerJarUpdate.discard(staged);
            o.addProperty("ok", false);
            o.addProperty("message", "Download is not a valid Fabric mod jar.");
            return o.toString();
        }
        ServerJarUpdate.Install installed = ServerJarUpdate.install(dir, staged, rel.jarName());
        if (!installed.ok()) {
            ServerJarUpdate.discard(staged);
            o.addProperty("ok", false);
            o.addProperty("message", "Could not install update: " + installed.message());
            return o.toString();
        }
        String removal = installed.message();
        AlminLog.info("[almin] web panel installed update {} ({} bytes) {}",
            rel.version(), fetched.bytes(), removal);
        updateJson = "";
        o.addProperty("ok", true);
        o.addProperty("version", rel.version());

        if (!restart) {
            o.addProperty("message", "Installed " + rel.version() + ". " + removal
                + " Restart the server to run it.");
            return o.toString();
        }
        // The panel is part of what just changed — it is served out of the jar
        // this replaced. Restarting is how both halves become the new version
        // at once; the page notices the version change and reloads itself onto
        // the new panel when the server comes back.
        boolean relaunch = ServerRelaunch.arm("an update to " + rel.version());
        o.addProperty("restarting", true);
        o.addProperty("relaunch", relaunch);
        o.addProperty("message", "Installed " + rel.version() + ". " + removal
            + (relaunch
                ? " Restarting now — this page reconnects on its own."
                : " Stopping now; whatever wrapper runs this server should start it again."));
        server.execute(() -> {
            server.getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal(
                    "[Almin] Updating to " + rel.version() + " — the server is restarting."),
                false);
            if (!relaunch) AlminExit.arm("an update from the web panel");
            server.halt(false);
        });
        return o.toString();
    }

    /**
     * Modrinth: search for a mod, or add one by slug or link.
     *
     * <p>Adding downloads the file that fits <em>this</em> server's Minecraft
     * version into {@code modfiles/} and then reads the jar's own
     * {@code fabric.mod.json} for the mod id. That last step is the point: the
     * id decides whether a client can tell it already has the mod, and no
     * amount of care typing it by hand is as reliable as asking the file.
     *
     * <p>Both calls reach the internet, so both run on a web thread with the
     * timeouts that live in {@link Modrinth} and {@link FileFetcher}. Neither
     * touches the server thread.
     */
    private void handleModrinth(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String action = body.has("action") ? body.get("action").getAsString() : "";
            String gameVersion = serverGameVersion();

            if ("search".equals(action)) {
                String query = body.has("query") ? body.get("query").getAsString().trim() : "";
                if (query.isEmpty()) { json(ex, 400, err("Type something to search for.")); return; }
                JsonArray hits = new JsonArray();
                for (Modrinth.Hit h : Modrinth.search(query, gameVersion)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("slug", h.slug());
                    o.addProperty("title", h.title());
                    o.addProperty("description", h.description());
                    o.addProperty("downloads", h.downloads());
                    o.addProperty("icon", h.iconUrl());
                    o.addProperty("page", "https://modrinth.com/mod/" + h.slug());
                    hits.add(o);
                }
                JsonObject out = new JsonObject();
                out.add("hits", hits);
                out.addProperty("gameVersion", gameVersion);
                json(ex, 200, out.toString());
                return;
            }

            // "add" advertises it to players; "server" installs it here.
            boolean toServer = "server".equals(action);
            if (!"add".equals(action) && !toServer) { json(ex, 400, err("Unknown action.")); return; }

            String link = body.has("link") ? body.get("link").getAsString().trim() : "";
            boolean required = body.has("required") && body.get("required").getAsBoolean();
            Modrinth.Resolved found = Modrinth.resolve(link, gameVersion);
            if (!found.ok()) { json(ex, 400, err(found.problem())); return; }

            Path dir = ModOffers.modFilesDir();
            if (dir == null) { json(ex, 409, err("Mod file storage isn't ready yet.")); return; }
            Path target = ModOffers.resolveModFile(found.filename());
            if (target == null) {
                json(ex, 400, err("Modrinth gave a filename Almin won't store: " + found.filename()));
                return;
            }

            AlminLog.info("[almin] fetching {} {} from Modrinth for MC {}",
                found.slug(), found.version(), gameVersion);

            if (toServer) {
                // Downloaded into modfiles/ first and then moved, so a failed
                // or half-finished download is never seen by Fabric.
                FileFetcher.FetchResult got =
                    FileFetcher.fetch(found.url(), target, server.getServerDirectory());
                if (!got.ok()) { json(ex, 400, err("Download failed: " + got.message())); return; }
                if (!UpdateChecker.looksLikeValidMod(target)) {
                    try { Files.deleteIfExists(target); } catch (IOException ignored) {}
                    json(ex, 400, err("That download isn't a Fabric mod jar."));
                    return;
                }
                ServerMods.Result put =
                    onServer(() -> ServerMods.install(server, target, found.filename(), true),
                        ServerMods.Result.fail("The server didn't answer in time."));
                if (!put.ok()) {
                    try { Files.deleteIfExists(target); } catch (IOException ignored) {}
                }
                JsonObject out = new JsonObject();
                out.addProperty("ok", put.ok());
                out.addProperty("message", put.message());
                out.addProperty("bytes", got.bytes());
                json(ex, put.ok() ? 200 : 400, out.toString());
                return;
            }

            FileFetcher.FetchResult fetched =
                FileFetcher.fetch(found.url(), target, server.getServerDirectory());
            if (!fetched.ok()) { json(ex, 400, err("Download failed: " + fetched.message())); return; }
            if (!UpdateChecker.looksLikeValidMod(target)) {
                try { Files.deleteIfExists(target); } catch (IOException ignored) {}
                json(ex, 400, err("That download isn't a Fabric mod jar."));
                return;
            }

            // The jar's own id, which is the whole reason for downloading it here.
            ModJars.Meta meta = ModJars.read(target);
            ModOffers.AdvertisedMod mod = new ModOffers.AdvertisedMod(
                meta.ok() ? meta.modId() : found.slug(),
                meta.ok() && !meta.name().isBlank() ? meta.name() : found.title(),
                meta.ok() && !meta.version().isBlank() ? meta.version() : found.version(),
                "", sha256Of(target), required, found.filename(),
                found.page(), "modrinth");
            ModOffers.AddResult r = ModOffers.add(mod);
            // The project's picture, kept locally. Best effort: a mod without
            // one is a blank square, and failing the add over that would be
            // the wrong trade. Already off the server thread.
            if (r == ModOffers.AddResult.OK) ModIcons.fetch(mod.modId(), found.iconUrl());

            JsonObject out = new JsonObject();
            out.addProperty("ok", r == ModOffers.AddResult.OK);
            out.addProperty("modId", mod.modId());
            out.addProperty("name", mod.name());
            out.addProperty("version", mod.version());
            out.addProperty("file", mod.file());
            out.addProperty("bytes", fetched.bytes());
            out.addProperty("message", r == ModOffers.AddResult.OK
                ? "Added " + mod.name() + " " + mod.version() + " as " + mod.modId()
                : "Downloaded, but the list refused it: " + r);
            json(ex, r == ModOffers.AddResult.OK ? 200 : 400, out.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /** The display name a player is currently wearing, or "" for none. */
    private static String maskOf(String uuid) {
        try {
            String mask = MaskConfig.maskFor(java.util.UUID.fromString(uuid));
            return mask == null ? "" : mask;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The Minecraft version this server is, as Modrinth spells it. */
    private String serverGameVersion() {
        try {
            return server.getServerVersion();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** SHA-256 of a file, so an advertisement can pin what it points at. */
    private static String sha256Of(Path file) {
        try (var in = Files.newInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- routes: player activity ----------

    /** Rows sent to a browser in one response. The page scrolls; it doesn't need all of them. */
    private static final int ACTIVITY_ROWS = 500;

    /**
     * Rows behind the map and the episode pass.
     *
     * <p>More than the list gets, because the two are read differently: a list
     * is scrolled and five hundred is already more than anyone reads, while
     * the map is a picture of a period and five hundred rows of a busy evening
     * is an hour of it. Marks are clustered when they crowd, so the extra rows
     * cost drawing time rather than legibility.
     */
    private static final int MAP_ROWS = 2500;

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
            String action = body.has("action") ? body.get("action").getAsString() : "";

            if ("admins".equals(action)) {
                // "until the server restarts" is a real option rather than a
                // convenience: the reason to record admins is usually one
                // afternoon, and a switch you have to remember to turn back
                // off is one that stays on.
                boolean temporary = body.has("temporary") && body.get("temporary").getAsBoolean();
                Boolean value = body.has("value") && !body.get("value").isJsonNull()
                    ? body.get("value").getAsBoolean() : null;
                if (temporary) {
                    ActivityLog.setTemporaryIncludeAdmins(value);
                    AlminLog.warn("[almin] web panel set activity admin tracking to {} for this run",
                        value == null ? "follow the setting" : value);
                } else {
                    if (value == null) { json(ex, 400, err("No value given.")); return; }
                    AlminConfig.get().activityIncludeAdmins = value;
                    AlminConfig.save();
                    AlminLog.warn("[almin] web panel set activity-include-admins to {}", value);
                }
                json(ex, 200, adminPolicyJson().toString());
                return;
            }

            if (!"clear".equals(action)) {
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
        for (ActivityEntry e : ActivityLog.recent(ACTIVITY_ROWS)) {
            JsonObject o = new JsonObject();
            o.addProperty("at", e.at());
            o.addProperty("player", e.player());
            o.addProperty("uuid", e.uuid());
            // The mask is a live lookup, not part of the row: masks change, and
            // the log is a record of who did something, not of what they were
            // called at the time.
            o.addProperty("mask", maskOf(e.uuid()));
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
        root.add("admins", adminPolicyJson());
        return root.toString();
    }

    /** Whether admins are recorded, and whether that came from the setting. */
    private static JsonObject adminPolicyJson() {
        ActivityAdminPolicy p = ActivityLog.adminPolicy();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("includeAdmins", p.includeAdmins());
        o.addProperty("temporary", p.temporary());
        o.addProperty("configured", p.configured());
        return o;
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

            // Everyone at once, for the timeline map at the top of the tab.
            if ("1".equals(queryParam(ex, "all"))) {
                // The player list belongs to the server thread; everything else
                // here is a synchronized snapshot and does not.
                List<Afk.Who> online = serverRunning
                    ? onServer(() -> Afk.online(server), List.<Afk.Who>of())
                    : List.<Afk.Who>of();
                json(ex, 200, allTracksJson(root, online == null ? List.of() : online).toString());
                return;
            }

            if (who == null || who.isBlank()) {
                root.addProperty("player", "");
                root.add("points", new JsonArray());
                root.add("actions", new JsonArray());
                json(ex, 200, root.toString());
                return;
            }

            JsonArray points = new JsonArray();
            for (PlayerTrackPoint p : PlayerTracks.of(who)) {
                JsonObject o = new JsonObject();
                o.addProperty("at", p.at());
                o.addProperty("dim", p.dim());
                o.addProperty("x", p.x());
                o.addProperty("y", p.y());
                o.addProperty("z", p.z());
                points.add(o);
            }

            JsonArray actions = new JsonArray();
            for (ActivityEntry e : ActivityLog.recent(ACTIVITY_ROWS)) {
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

    /**
     * Every tracked player's path, and every placed action, on one clock.
     *
     * <p>The per-player map answers "where has this person been". This answers
     * the question you actually start with — "what happened here, and who was
     * around" — which needs everyone on the same timeline or it answers
     * nothing.
     */
    private JsonObject allTracksJson(JsonObject root, List<Afk.Who> online) {
        long from = Long.MAX_VALUE, to = 0;

        // Name to UUID, so the map can draw a face at each path's head rather
        // than a coloured dot with a label beside it.
        JsonObject ids = new JsonObject();
        JsonObject tracks = new JsonObject();
        for (String name : PlayerTracks.tracked().keySet()) {
            JsonArray points = new JsonArray();
            for (PlayerTrackPoint p : PlayerTracks.of(name)) {
                JsonObject o = new JsonObject();
                o.addProperty("at", p.at());
                o.addProperty("dim", p.dim());
                o.addProperty("x", p.x());
                o.addProperty("y", p.y());
                o.addProperty("z", p.z());
                points.add(o);
                from = Math.min(from, p.at());
                to = Math.max(to, p.at());
            }
            if (points.isEmpty()) continue;
            tracks.add(name, points);
            java.util.UUID id = PlayerTracks.uuidOf(name);
            if (id != null) ids.addProperty(name, id.toString());
        }

        JsonArray actions = new JsonArray();
        for (ActivityEntry e : ActivityLog.recent(MAP_ROWS)) {
            if (e.dim() == null || e.dim().isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("at", e.at());
            o.addProperty("player", e.player());
            o.addProperty("mask", maskOf(e.uuid()));
            o.addProperty("action", e.action());
            o.addProperty("detail", e.detail());
            o.addProperty("dim", e.dim());
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("z", e.z());
            o.addProperty("count", e.count());
            actions.add(o);
            from = Math.min(from, e.at());
            to = Math.max(to, e.at());
        }

        // Who is on right now, and who among them has stopped moving. Live
        // rather than historical: the overlay answers "who is here", which the
        // timeline cannot, because a path ends when someone leaves.
        JsonArray who = new JsonArray();
        for (Afk.Who w : online) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.name());
            o.addProperty("uuid", w.uuid());
            o.addProperty("afk", w.afk());
            o.addProperty("stillSince", w.stillSince());
            o.addProperty("dim", w.dim());
            o.addProperty("x", w.x());
            o.addProperty("y", w.y());
            o.addProperty("z", w.z());
            o.addProperty("mask", maskOf(w.uuid()));
            who.add(o);
        }

        root.addProperty("all", true);
        root.add("tracks", tracks);
        root.add("ids", ids);
        root.add("actions", actions);
        root.add("online", who);
        root.addProperty("afkSeconds", AlminConfig.get().activityAfkSeconds);
        root.addProperty("from", from == Long.MAX_VALUE ? 0 : from);
        root.addProperty("to", to);
        // The clock, not the last thing that was recorded. Live mode follows
        // this: on a quiet server the newest row can be an hour old, and a
        // cursor pinned to it would say the map was showing an hour ago.
        root.addProperty("now", System.currentTimeMillis());
        root.add("admins", adminPolicyJson());
        return root;
    }

    // ---------- routes: what it all meant ----------

    /**
     * Episodes, and a summary of them if a model is configured.
     *
     * <p>GET always answers, model or no model: the episodes are worked out
     * here from the log and cost nothing, and they are most of the value. The
     * summary is whatever the model last said, which may be nothing.
     *
     * <p>POST asks the model now. That is the only thing on this endpoint that
     * leaves the machine, and it happens on this thread — a web thread, never
     * the server thread, because it waits on a network round trip that can
     * take a minute.
     */
    private void handleInsights(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            boolean run = "POST".equals(ex.getRequestMethod());
            JsonObject body = run ? readBody(ex) : new JsonObject();
            AiInsights.Scope scope = scopeOf(ex, body);

            List<ActivityEntry> rows = ActivityLog.recent(MAP_ROWS);
            List<Episodes.Episode> episodes = Episodes.of(rows);
            episodes = merge(episodes, Episodes.ofMovement(PlayerTracks.everyone(4000)));

            JsonObject root = new JsonObject();
            root.add("episodes", episodesJson(episodes));
            root.add("ai", aiStatusJson());
            root.addProperty("scope", scope.key());

            AiInsights.Report report;
            if (run) {
                AlminConfig cfg = AlminConfig.get();
                if (!cfg.aiEnabled) {
                    json(ex, 409, err("Summaries are off. Turn on ai-enabled first."));
                    return;
                }
                long from = Long.MAX_VALUE, to = 0;
                for (ActivityEntry e : rows) {
                    from = Math.min(from, e.at());
                    to = Math.max(to, e.at());
                }
                int online = serverRunning
                    ? onServer(() -> server.getPlayerCount(), 0) : 0;
                report = AiInsights.summarise(scope, episodes, rows,
                    from == Long.MAX_VALUE ? to : from, to, online, true);
            } else {
                report = AiInsights.cached(scope);
            }
            if (report != null) root.add("report", reportJson(report));
            json(ex, 200, root.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Which subject the panel is asking about.
     *
     * <p>Read from the query string for a GET and the body for a POST, so the
     * same three fields work for "what is cached for this player" and "go and
     * ask about this player".
     */
    private static AiInsights.Scope scopeOf(HttpExchange ex, JsonObject body) {
        String kind = pick(ex, body, "scope");
        if ("player".equals(kind)) {
            String who = pick(ex, body, "player");
            if (!who.isEmpty()) return AiInsights.Scope.of(who);
        } else if ("area".equals(kind)) {
            int x = number(pick(ex, body, "x"), 0);
            int z = number(pick(ex, body, "z"), 0);
            int r = Math.max(16, Math.min(8192, number(pick(ex, body, "r"), 256)));
            return AiInsights.Scope.area(pick(ex, body, "dim"), x, z, r);
        }
        return AiInsights.Scope.all();
    }

    private static String pick(HttpExchange ex, JsonObject body, String key) {
        if (body != null && body.has(key) && !body.get(key).isJsonNull()
            && body.get(key).isJsonPrimitive()) {
            return body.get(key).getAsString().trim();
        }
        String q = queryParam(ex, key);
        return q == null ? "" : q.trim();
    }

    private static int number(String s, int fallback) {
        try {
            return s == null || s.isEmpty() ? fallback : (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longNumber(String s, long fallback) {
        try {
            return s == null || s.isEmpty() ? fallback : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Answers an Activity question and identifies its supporting filters.
     *
     * <p>The panel can show the answer alone or apply the returned filter. The
     * selected subject and timeline window are applied here, against the
     * server's own rows, rather than trusting a browser-supplied activity log.
     */
    private void handleFind(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String question = body.has("question") ? body.get("question").getAsString() : "";
            AiInsights.Scope scope = scopeOf(ex, body);
            if (!AlminConfig.get().aiEnabled) {
                json(ex, 409, err("Summaries are off. Turn on ai-enabled first."));
                return;
            }
            List<ActivityEntry> allRows = ActivityLog.recent(MAP_ROWS);
            List<Episodes.Episode> allEpisodes = Episodes.of(allRows);
            allEpisodes = merge(allEpisodes, Episodes.ofMovement(PlayerTracks.everyone(4000)));

            long dataFrom = Long.MAX_VALUE, dataTo = 0;
            for (ActivityEntry e : allRows) {
                dataFrom = Math.min(dataFrom, e.at());
                dataTo = Math.max(dataTo, e.at());
            }
            for (Episodes.Episode e : allEpisodes) {
                dataFrom = Math.min(dataFrom, e.from());
                dataTo = Math.max(dataTo, e.to());
            }
            if (dataFrom == Long.MAX_VALUE) dataFrom = dataTo;
            long from = longNumber(pick(ex, body, "from"), dataFrom);
            long to = longNumber(pick(ex, body, "to"), dataTo);
            if (from < dataFrom) from = dataFrom;
            if (to <= 0 || to > dataTo) to = dataTo;
            if (from > to) { from = dataFrom; to = dataTo; }

            List<ActivityEntry> rows = new ArrayList<>();
            for (ActivityEntry e : allRows) {
                if (scope.holds(e) && e.at() >= from && e.at() <= to) rows.add(e);
            }
            List<Episodes.Episode> episodes = new ArrayList<>();
            for (Episodes.Episode e : allEpisodes) {
                if (scope.holds(e) && e.to() >= from && e.from() <= to) episodes.add(e);
            }
            AiInsights.Lens lens = AiInsights.look(question, scope, episodes, rows, from, to);
            JsonObject o = new JsonObject();
            o.addProperty("question", lens.question());
            o.addProperty("reply", lens.reply());
            o.addProperty("error", lens.error() == null ? "" : lens.error());
            o.add("players", strings(lens.players()));
            o.add("actions", strings(lens.actions()));
            o.add("items", strings(lens.items()));
            o.add("kinds", strings(lens.kinds()));
            JsonArray eps = new JsonArray();
            for (Long at : lens.episodes()) eps.add(at);
            o.add("episodes", eps);
            json(ex, lens.ok() ? 200 : 400, o.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private static JsonArray strings(List<String> list) {
        JsonArray arr = new JsonArray();
        for (String s : list) arr.add(s);
        return arr;
    }

    /**
     * What the model makes of one client's mod list.
     *
     * <p>Never automatic. The rest of the AI work runs on a timer once it is
     * switched on; this one is a person deciding to point a model at another
     * person's computer, and that should be a deliberate act every time.
     */
    private void handleModReview(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            java.util.UUID id = Heads.parseUuid(body.has("uuid")
                ? body.get("uuid").getAsString() : "");
            if (id == null) { json(ex, 400, err("Not a UUID.")); return; }
            ClientProfiles.Profile p = ClientProfiles.of(id);
            if (p == null) { json(ex, 404, err("That client has not reported anything.")); return; }
            AiInsights.ModReview review = AiInsights.review(p.name(), p.present(), p.removed());
            JsonObject o = new JsonObject();
            o.addProperty("generated", review.generated());
            o.addProperty("summary", review.summary());
            o.addProperty("error", review.error() == null ? "" : review.error());
            JsonArray flags = new JsonArray();
            for (AiInsights.ModFlag f : review.flags()) {
                JsonObject j = new JsonObject();
                j.addProperty("id", f.id());
                j.addProperty("level", f.level());
                j.addProperty("why", f.why());
                flags.add(j);
            }
            o.add("flags", flags);
            json(ex, review.ok() ? 200 : 400, o.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /** Episodes and movement on one list, most notable first. */
    private static List<Episodes.Episode> merge(List<Episodes.Episode> a,
                                                List<Episodes.Episode> b) {
        List<Episodes.Episode> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        out.sort(java.util.Comparator.comparingInt(Episodes.Episode::weight).reversed()
            .thenComparing(java.util.Comparator.comparingLong(Episodes.Episode::to).reversed()));
        return out;
    }

    private static JsonArray episodesJson(List<Episodes.Episode> episodes) {
        JsonArray arr = new JsonArray();
        for (Episodes.Episode e : episodes) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", e.kind());
            o.addProperty("headline", e.headline());
            o.addProperty("player", e.player());
            o.addProperty("mask", maskOf(e.uuid()));
            o.addProperty("uuid", e.uuid());
            o.addProperty("dim", e.dim());
            o.addProperty("from", e.from());
            o.addProperty("to", e.to());
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("z", e.z());
            o.addProperty("events", e.events());
            o.addProperty("weight", e.weight());
            o.addProperty("tool", e.tool());
            arr.add(o);
        }
        return arr;
    }

    private static JsonObject reportJson(AiInsights.Report r) {
        JsonObject o = new JsonObject();
        o.addProperty("generated", r.generated());
        o.addProperty("summary", r.summary());
        o.addProperty("model", r.model());
        o.addProperty("provider", r.provider());
        o.addProperty("error", r.error() == null ? "" : r.error());
        JsonArray moments = new JsonArray();
        for (AiInsights.Moment m : r.moments()) {
            JsonObject j = new JsonObject();
            j.addProperty("at", m.at());
            j.addProperty("label", m.label());
            j.addProperty("why", m.why());
            j.addProperty("player", m.player());
            j.addProperty("dim", m.dim());
            j.addProperty("x", m.x());
            j.addProperty("y", m.y());
            j.addProperty("z", m.z());
            j.addProperty("weight", m.weight());
            moments.add(j);
        }
        o.add("moments", moments);
        JsonArray means = new JsonArray();
        for (AiInsights.Meaning m : r.meanings()) {
            JsonObject j = new JsonObject();
            j.addProperty("at", m.at());
            j.addProperty("player", m.player());
            j.addProperty("means", m.means());
            means.add(j);
        }
        o.add("sequences", means);
        JsonArray found = new JsonArray();
        for (AiInsights.Found f : r.found()) {
            JsonObject j = new JsonObject();
            j.addProperty("from", f.from());
            j.addProperty("to", f.to());
            j.addProperty("player", f.player());
            j.addProperty("label", f.label());
            j.addProperty("why", f.why());
            found.add(j);
        }
        o.add("patterns", found);
        o.addProperty("scope", r.scope());
        return o;
    }

    /**
     * What is configured, and what turning it on would mean.
     *
     * <p>The key itself is never in here — only whether there is one.
     */
    private static JsonObject aiStatusJson() {
        AlminConfig cfg = AlminConfig.get();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", cfg.aiEnabled);
        o.addProperty("provider", cfg.aiProvider);
        o.addProperty("model", cfg.aiModel);
        o.addProperty("baseUrl", cfg.aiBaseUrl);
        o.addProperty("sendChat", cfg.aiSendChat);
        o.addProperty("sendSceneImages", cfg.aiSendSceneImages);
        o.addProperty("autoMinutes", cfg.aiAutoMinutes);
        o.addProperty("timeoutSeconds", cfg.aiTimeoutSeconds);
        o.addProperty("hasKey", AiInsights.hasKey());
        o.addProperty("problem", AiInsights.problem());
        return o;
    }

    /**
     * Sets or clears the API key.
     *
     * <p>Secure sessions only, like the admin password: this is a credential
     * going over the wire, and everything else about it is pointless if that
     * part is in the clear.
     */
    private void handleAiKey(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method\"}"); return; }
            JsonObject body = readBody(ex);
            String key = body.has("key") && !body.get("key").isJsonNull()
                ? body.get("key").getAsString() : "";
            boolean ok = AiInsights.setKey(key);
            if (!ok) { json(ex, 500, err("Could not write the key file.")); return; }
            AlminLog.info("[almin] AI key {} from the panel by {}",
                key.isBlank() ? "cleared" : "set", clientKey(ex));
            json(ex, 200, "{\"ok\":true,\"hasKey\":" + AiInsights.hasKey() + "}");
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Recent AI wire exchanges, with credential values omitted.
     *
     * <p>The request and response bodies can contain player activity and chat,
     * so this is an authenticated admin route and follows the same transport
     * gate as the key itself. Header names are useful evidence; values are
     * deliberately never retained by the diagnostic recorder.
     */
    private void handleAiDiagnostics(HttpExchange ex) throws IOException {
        try {
            if (!requireAuthSecure(ex)) return;
            if (!"GET".equals(ex.getRequestMethod())) {
                json(ex, 405, "{\"error\":\"method\"}");
                return;
            }
            JsonObject root = new JsonObject();
            JsonArray rows = new JsonArray();
            for (AiTransport.Diagnostic d : AiTransport.diagnostics()) {
                JsonObject o = new JsonObject();
                o.addProperty("at", d.at());
                o.addProperty("provider", d.provider());
                o.addProperty("model", d.model());
                o.addProperty("url", d.url());
                o.add("requestHeaders", strings(d.requestHeaders()));
                o.addProperty("requestBody", d.requestBody());
                o.addProperty("status", d.status());
                o.add("responseHeaders", strings(d.responseHeaders()));
                o.addProperty("responseBody", d.responseBody());
                o.addProperty("elapsedMs", d.elapsedMs());
                o.addProperty("error", d.error());
                rows.add(o);
            }
            root.add("rows", rows);
            json(ex, 200, root.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * What one player's client is running, and what it used to be.
     *
     * <p>Everything here was said by that client. It is the answer to "why is
     * it crashing for them", and it is not evidence of anything: a modified
     * client can report whatever it likes.
     */
    private void handleClient(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            java.util.UUID id = Heads.parseUuid(queryParam(ex, "uuid"));
            if (id == null) { json(ex, 400, err("Not a UUID.")); return; }
            ClientProfiles.Profile p = ClientProfiles.of(id);
            JsonObject root = new JsonObject();
            root.addProperty("enabled", AlminConfig.get().clientReport);
            root.addProperty("historyDays", AlminConfig.get().clientModHistoryDays);
            if (p == null) {
                root.addProperty("known", false);
                json(ex, 200, root.toString());
                return;
            }
            root.addProperty("known", true);
            root.addProperty("name", p.name());
            root.addProperty("at", p.at());
            root.addProperty("minecraft", p.minecraft());
            root.addProperty("loader", p.loader());
            root.addProperty("launcher", p.launcher());
            root.addProperty("os", p.os());
            root.addProperty("osVersion", p.osVersion());
            root.addProperty("arch", p.arch());
            root.addProperty("java", p.java());
            root.addProperty("cores", p.cores());
            root.addProperty("memoryMb", p.memoryMb());

            java.util.TreeSet<String> banned = ClientProfiles.restrictedSet();
            JsonArray mods = new JsonArray();
            for (ClientProfiles.Mod m : p.present()) mods.add(modJson(m, banned));
            root.add("mods", mods);
            JsonArray gone = new JsonArray();
            for (ClientProfiles.Mod m : p.removed()) gone.add(modJson(m, banned));
            root.add("removed", gone);
            json(ex, 200, root.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    private static JsonObject modJson(ClientProfiles.Mod m, java.util.Set<String> banned) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id());
        o.addProperty("version", m.version());
        o.addProperty("firstSeen", m.firstSeen());
        o.addProperty("removedAt", m.removedAt());
        o.addProperty("parent", m.parent() == null ? "" : m.parent());
        o.addProperty("restricted", banned.contains(m.id().toLowerCase(java.util.Locale.ROOT)));
        return o;
    }

    /**
     * What colour each block is, by the name the log records.
     *
     * <p>For the isometric view, which has to draw a block and knows only what
     * the row called it. Sent once and cached hard: it is a property of the
     * server's own registry and cannot change while it is running.
     */
    private void handleBlocks(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            JsonObject out = new JsonObject();
            for (Map.Entry<String, Integer> e : BlockTextures.palette().entrySet()) {
                out.addProperty(e.getKey(), String.format("#%06x", e.getValue() & 0xFFFFFF));
            }
            JsonObject root = new JsonObject();
            root.add("blocks", out);
            root.addProperty("textures", BlockTextures.source());
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
            json(ex, 200, root.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /** One actual resource-pack block face for the isometric activity scene. */
    private void handleBlock(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            byte[] png = BlockTextures.block(queryParam(ex, "name"), queryParam(ex, "face"));
            if (png == null) { json(ex, 404, err("No texture for that block.")); return; }
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
            image(ex, "image/png", png);
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * One item texture, for the tool drawn on a sequence badge.
     *
     * <p>404 when this server has no textures, which the panel takes as "draw
     * your own" rather than as a failure.
     */
    private void handleItem(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            byte[] png = BlockTextures.item(queryParam(ex, "name"));
            if (png == null) { json(ex, 404, err("No such item texture.")); return; }
            image(ex, "image/png", png);
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Minecraft's own settings file.
     *
     * <p>Not Almin's, which is the point: the panel already offers a file
     * browser this could be edited in, so offering it properly — typed
     * controls, a name per row, and a sentence saying it lands at the next
     * restart — is strictly better than making someone find the file.
     */
    private void handleProperties(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (server == null) { json(ex, 503, err("No server here.")); return; }
            java.nio.file.Path file = ServerProperties.fileFor(server);

            if ("POST".equals(ex.getRequestMethod())) {
                JsonObject body = readBody(ex);
                if (!body.has("set") || !body.get("set").isJsonObject()) {
                    json(ex, 400, err("Nothing to set."));
                    return;
                }
                Map<String, String> changes = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> e
                        : body.getAsJsonObject("set").entrySet()) {
                    if (!e.getValue().isJsonPrimitive()) continue;
                    changes.put(e.getKey(), e.getValue().getAsString());
                }
                if (changes.size() > 200) { json(ex, 400, err("Too many at once.")); return; }
                int changed;
                try {
                    changed = ServerProperties.write(file, changes);
                } catch (IOException bad) {
                    json(ex, 400, err(bad.getMessage()));
                    return;
                }
                AlminLog.info("[almin] server.properties: {} value(s) changed from the panel by {}",
                    changed, clientKey(ex));
                json(ex, 200, "{\"ok\":true,\"changed\":" + changed + "}");
                return;
            }

            JsonArray rows = new JsonArray();
            for (ServerProperties.Entry e : ServerProperties.read(file)) {
                JsonObject o = new JsonObject();
                o.addProperty("key", e.key());
                o.addProperty("value", e.value());
                o.addProperty("type", e.type());
                o.addProperty("secret", e.secret());
                rows.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("rows", rows);
            root.addProperty("file", "server.properties");
            json(ex, 200, root.toString());
        } catch (IOException missing) {
            json(ex, 404, err(missing.getMessage()));
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * The pictures of the ground the map is drawn over.
     *
     * <p>{@code /api/map} lists what exists, so the page can pick the one that
     * matches wherever the timeline is pointing; {@code /api/map?at=…&dim=…}
     * returns that picture as a PNG. Two shapes on one route because the list
     * is small and the choice is entirely the browser's.
     */
    private void handleMap(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            String atParam = queryParam(ex, "at");
            String dim = queryParam(ex, "dim");

            if (atParam == null || atParam.isBlank()) {
                JsonArray arr = new JsonArray();
                for (WorldSnapshots.Shot shot : WorldSnapshots.all()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("at", shot.at());
                    o.addProperty("dim", shot.dim());
                    o.addProperty("minX", shot.minX());
                    o.addProperty("minZ", shot.minZ());
                    o.addProperty("span", shot.span());
                    arr.add(o);
                }
                JsonObject root = new JsonObject();
                root.add("shots", arr);
                root.addProperty("every", AlminConfig.get().mapSnapshotSeconds);
                // Whether the ground is drawn from the game's own textures or
                // from the map palette. Worth saying, because the difference
                // is large and the answer depends on whether this server
                // happens to have a resource pack lying around.
                root.addProperty("textures", BlockTextures.source());
                root.addProperty("texturedBlocks", BlockTextures.loaded());
                json(ex, 200, root.toString());
                return;
            }

            long at;
            try {
                at = Long.parseLong(atParam.trim());
            } catch (NumberFormatException e) {
                json(ex, 400, err("Not a timestamp."));
                return;
            }
            WorldSnapshots.Shot shot = WorldSnapshots.at(dim, at);
            // The same picture, but the shape of the ground rather than its
            // colour, for the isometric view.
            boolean shape = "1".equals(queryParam(ex, "height"));
            byte[] png = shot == null ? null
                : (shape ? WorldSnapshots.heights(shot) : WorldSnapshots.read(shot));
            if (png == null) {
                json(ex, 404, err(shape ? "No shape recorded for that moment."
                                        : "No picture of that moment."));
                return;
            }
            // Each picture is immutable once written, and the browser asks for
            // a lot of them while scrubbing the timeline.
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(png);
            }
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /** Status and one-time configuration for the separately installed BlueMap mod. */
    private void handleBlueMap(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if ("GET".equals(ex.getRequestMethod())) {
                json(ex, 200, BlueMapIntegration.status(server, port).json().toString());
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                json(ex, 405, "{\"error\":\"method\"}");
                return;
            }
            if (!secure(ex)) {
                json(ex, 403, err("BlueMap can only be configured over HTTPS or from the machine itself."));
                return;
            }
            if (!serverRunning) {
                json(ex, 503, err("The Minecraft server is not running."));
                return;
            }
            JsonObject body = readBody(ex);
            String action = body.has("action") ? body.get("action").getAsString() : "";
            if (!"configure".equals(action) && !"accept-download".equals(action)
                    && !"reset".equals(action)) {
                json(ex, 400, err("Unknown BlueMap action."));
                return;
            }
            BlueMapIntegration.Result result;
            if ("reset".equals(action)) {
                result = onServer(() -> BlueMapIntegration.reset(server),
                    BlueMapIntegration.Result.fail("The server did not answer the reset request."));
            } else if ("accept-download".equals(action)) {
                result = BlueMapIntegration.acceptDownload(server);
            } else {
                result = BlueMapIntegration.configure(server, port);
            }
            JsonObject out = new JsonObject();
            out.addProperty("ok", result.ok());
            out.addProperty("message", result.message());
            out.addProperty("restartRequired", result.restartRequired());
            out.addProperty("port", result.port());
            out.add("status", BlueMapIntegration.status(server, port).json());
            json(ex, result.ok() ? 200 : 400, out.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Authenticates on a normal panel worker, then hands the long-lived tile
     * or SSE stream to BlueMap's own pool and returns without closing it.
     */
    private void handleBlueMapProxy(HttpExchange ex) throws IOException {
        if (!requireAuth(ex)) {
            ex.close();
            return;
        }
        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            json(ex, 405, "{\"error\":\"method\"}");
            ex.close();
            return;
        }
        try {
            blueMapPool().execute(() -> {
                BlueMapIntegration.Status status = BlueMapIntegration.status(server, port);
                if (!status.ready()) {
                    try {
                        json(ex, 503, err(status.message()));
                    } catch (IOException ignored) {
                        // The browser left while status was being checked.
                    } finally {
                        ex.close();
                    }
                    return;
                }
                BlueMapProxy.forward(ex, status.port());
            });
        } catch (RuntimeException rejected) {
            json(ex, 503, err("BlueMap's web workers are stopping."));
            ex.close();
        }
    }

    /**
     * Exposed blocks in the already-loaded world around one activity scene.
     *
     * <p>This is a live context layer, not a historical reconstruction. The
     * activity blocks remain the historical evidence; this answers the useful
     * second question, "what is around that work now?". The capture itself is
     * handed to the server thread, and {@link SceneContext} refuses to load
     * chunks while doing it.
     */
    private void handleSceneContext(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!"GET".equals(ex.getRequestMethod())) {
                json(ex, 405, "{\"error\":\"method\"}");
                return;
            }
            if (!serverRunning) {
                json(ex, 503, err("The Minecraft server is not running."));
                return;
            }
            String dim = queryParam(ex, "dim").trim();
            String rawX = queryParam(ex, "x").trim();
            String rawZ = queryParam(ex, "z").trim();
            if (dim.isEmpty() || rawX.isEmpty() || rawZ.isEmpty()) {
                json(ex, 400, err("Dimension, x, and z are required."));
                return;
            }
            int x = number(rawX, 0);
            int z = number(rawZ, 0);
            int minY = number(queryParam(ex, "minY"), -64);
            int maxY = number(queryParam(ex, "maxY"), 320);
            int radius = number(queryParam(ex, "radius"), 16);
            JsonObject out = onServer(
                () -> SceneContext.capture(server, dim, x, z, minY, maxY, radius), null);
            if (out == null) {
                json(ex, 404, err("That dimension is not loaded."));
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            json(ex, 200, out.toString());
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * A player's face, as a small PNG.
     *
     * <p>Behind the login: which UUIDs this server knows about is not public
     * information, and an open endpoint would be a way of asking.
     *
     * <p>The one thing that has to happen on the server thread is reading a
     * connected player's profile, which is where the skin already is for
     * anyone online. Everything after that — Mojang, the download, the
     * decode — happens here, on a web thread, because it blocks.
     */
    private void handleHead(HttpExchange ex) throws IOException {
        try {
            if (!requireAuth(ex)) return;
            if (!AlminConfig.get().webPlayerHeads) {
                json(ex, 404, err("Player faces are turned off (web-player-heads)."));
                return;
            }
            String name = queryParam(ex, "name");
            String rawId = queryParam(ex, "uuid");
            java.util.UUID id = Heads.parseUuid(rawId);
            if (id == null) {
                // A uuid that was given and does not parse is a mistake worth
                // saying so about; no uuid at all is the mask case, where a
                // name is the only thing there is to go on.
                if (rawId != null && !rawId.isBlank()) {
                    json(ex, 400, err("Not a UUID."));
                    return;
                }
                byte[] byName = name == null ? null : Heads.byName(name);
                if (byName == null) { json(ex, 404, err("No face for that name.")); return; }
                image(ex, "image/png", byName);
                return;
            }
            String texture = serverRunning
                ? onServer(() -> Heads.textureFromProfile(server, id), "")
                : "";
            byte[] png = Heads.head(id, name, texture == null ? "" : texture);
            if (png == null) { json(ex, 404, err("No skin for that player.")); return; }
            image(ex, "image/png", png);
        } catch (Throwable t) {
            fault(ex, t);
        } finally {
            ex.close();
        }
    }

    /**
     * Sends image bytes.
     *
     * <p>{@code nosniff} matters more here than anywhere else in the panel:
     * these bytes came from a jar or from the internet, they are served from
     * the panel's own origin, and the content type was decided by looking at
     * the bytes rather than by believing a header.
     */
    private static void image(HttpExchange ex, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; sandbox");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
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
            // Whether this client can hear Almin at all. It is the same test
            // the join handler uses to decide whether to send them anything.
            o.addProperty("hasMod", net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
                .canSend(p, ServerVersionPayload.TYPE));
            o.addProperty("reported", ClientProfiles.of(p.getUUID()) != null);
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
                // Somebody who is offline cannot be asked; what is known is
                // whether they ever told us.
                o.addProperty("reported", ClientProfiles.of(e.getKey()) != null);
                history.add(o);
            }
        }
        root.add("history", history);
        root.addProperty("maxPlayers", server.getMaxPlayers());
        root.addProperty("clientReport", AlminConfig.get().clientReport);
        root.addProperty("requireClientMod", AlminConfig.get().requireClientMod);
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
            if (Files.exists(file)) {
                warnIfCaddyTargetDiffers(file);
                return;
            }
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

    /**
     * Calls out the other way a local success can look like a dead website:
     * an older generated proxy file still forwarding to a port Almin used in
     * a previous run. The file is explicitly user-editable, so this diagnoses
     * the mismatch without rewriting or reloading somebody's proxy for them.
     */
    private void warnIfCaddyTargetDiffers(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            java.util.regex.Matcher target = java.util.regex.Pattern.compile(
                "(?m)^\\s*reverse_proxy\\s+127\\.0\\.0\\.1:(\\d+)\\s*$").matcher(text);
            if (!target.find()) return;
            int configured = Integer.parseInt(target.group(1));
            if (configured == port) return;
            String message = "config/almin/Caddyfile forwards to 127.0.0.1:" + configured
                + " but the verified panel is on 127.0.0.1:" + port
                + "; its public HTTPS address will stay unavailable until that target is "
                + "changed and Caddy is reloaded";
            AlminLog.warn("[almin] {}", message);
            CONSOLE.warn("[almin] {}", message);
        } catch (IOException | RuntimeException e) {
            AlminLog.warn("[almin] could not check the existing Caddy target: {}", e.toString());
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
