package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A read-only web view of the same dashboard {@code /almin} shows in-game,
 * served over plain HTTP by the JDK's built-in server.
 *
 * <h3>What it can and cannot do</h3>
 * Every route is a GET and every response is a rendering of state the server
 * already published to admins in-game: the dashboard rows and the tail of the
 * console. There are no POST routes, nothing here touches the world, the
 * filesystem, or the command dispatcher, and nothing here can change a
 * setting. Adding a write path would turn this into remote command execution
 * over HTTP, which is a much bigger promise than a dashboard — so it isn't
 * here.
 *
 * <h3>Access</h3>
 * A token generated on first startup (see {@code AlminConfig}) is required on
 * every request, supplied as {@code ?token=} once and then held in a cookie.
 * The token is compared in constant time. The bind address defaults to all
 * interfaces so the page is reachable from a browser on another machine; set
 * {@code web-ui-bind} to {@code 127.0.0.1} to restrict it to the server host.
 * There is no TLS — the token and the page travel in clear text, so treat this
 * as a tool for a trusted network or an SSH tunnel, not the open internet.
 *
 * <h3>Threading</h3>
 * HTTP handlers never touch live server state. A snapshot is rebuilt on the
 * server thread every {@link #REFRESH_TICKS} ticks and published to a volatile
 * field, which the handlers serialise. That keeps world access on the thread
 * that owns it and makes a slow client unable to stall a tick.
 */
public final class WebUi {
    /** How often the served snapshot is rebuilt, in ticks (40 = ~2s). */
    private static final int REFRESH_TICKS = 40;

    /** Console lines kept in the snapshot. */
    private static final int CONSOLE_LINES = 300;

    private static final String COOKIE = "almin_token";

    private static volatile WebUi instance;

    private final HttpServer http;
    private final String token;
    private final String bind;
    private final int port;

    /** Latest snapshot, rebuilt on the server thread. Never null once started. */
    private volatile String snapshotJson = "{\"rows\":[],\"console\":[],\"age\":0}";
    private int tickCounter = 0;

    private WebUi(HttpServer http, String token, String bind, int port) {
        this.http = http;
        this.token = token;
        this.bind = bind;
        this.port = port;
    }

    /**
     * Starts the dashboard if it's enabled and configured. Never throws — a
     * port that's already taken logs a warning and leaves the game running.
     */
    public static synchronized void start(MinecraftServer server) {
        if (instance != null) return;
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.webUiEnabled) {
            AlminLog.info("[almin] web dashboard disabled by config");
            return;
        }
        if (cfg.webUiToken == null || cfg.webUiToken.isBlank()) {
            AlminLog.warn("[almin] web dashboard not started: no access token in config");
            return;
        }
        String bind = (cfg.webUiBind == null || cfg.webUiBind.isBlank()) ? "0.0.0.0" : cfg.webUiBind.trim();
        int port = cfg.webUiPort;
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 16);
            WebUi ui = new WebUi(http, cfg.webUiToken, bind, port);
            http.createContext("/", ui::handleRoot);
            http.createContext("/api/state", ui::handleState);
            http.setExecutor(Executors.newFixedThreadPool(2, daemonFactory()));
            http.start();
            instance = ui;
            ServerTickEvents.END_SERVER_TICK.register(ui::onTick);
            ui.rebuild(server);   // serve real data from the first request
            AlminLog.info("[almin] web dashboard on http://{}:{}/?token={}", bind, port, cfg.webUiToken);
        } catch (IOException e) {
            AlminLog.warn("[almin] web dashboard could not bind {}:{} — {}", bind, port, e.getMessage());
        } catch (RuntimeException e) {
            AlminLog.warn("[almin] web dashboard failed to start: {}", e.toString());
        }
    }

    /** Stops the dashboard. Safe to call when it never started. */
    public static synchronized void stop() {
        WebUi ui = instance;
        if (ui == null) return;
        instance = null;
        try {
            ui.http.stop(0);
        } catch (RuntimeException ignored) {
            // Shutting down anyway.
        }
    }

    /** The browsable URL, or null when the dashboard isn't running. */
    public static String url() {
        WebUi ui = instance;
        if (ui == null) return null;
        // 0.0.0.0 isn't something you can type into a browser; name the host.
        String host = ui.bind.equals("0.0.0.0") ? "<server-address>" : ui.bind;
        return "http://" + host + ":" + ui.port + "/?token=" + ui.token;
    }

    /** Whether the dashboard is currently listening. */
    public static boolean running() {
        return instance != null;
    }

    /** Port it's listening on, or -1. */
    public static int port() {
        WebUi ui = instance;
        return ui == null ? -1 : ui.port;
    }

    private static ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r, "Almin-web");
            t.setDaemon(true);
            return t;
        };
    }

    // ---------- snapshot ----------

    private void onTick(MinecraftServer server) {
        if (++tickCounter < REFRESH_TICKS) return;
        tickCounter = 0;
        rebuild(server);
    }

    /** Runs on the server thread: reads live state, renders it to JSON. */
    private void rebuild(MinecraftServer server) {
        try {
            JsonObject root = new JsonObject();
            JsonArray rows = new JsonArray();
            for (DashboardPayload.Row r : Dashboard.build(server, null).rows()) {
                JsonObject o = new JsonObject();
                o.addProperty("kind", r.kind());
                o.addProperty("label", r.label());
                o.addProperty("value", r.value());
                // Drop the alpha byte — CSS wants #rrggbb.
                o.addProperty("accent", r.accent() == 0 ? "" : String.format("#%06x", r.accent() & 0xFFFFFF));
                rows.add(o);
            }
            root.add("rows", rows);

            JsonArray console = new JsonArray();
            ConsoleTap tap = ConsoleTap.get();
            if (tap != null) {
                for (String line : tap.recentLines(CONSOLE_LINES)) console.add(line);
            }
            root.add("console", console);
            root.addProperty("generated", System.currentTimeMillis());
            snapshotJson = root.toString();
        } catch (RuntimeException e) {
            // A broken snapshot must never take the tick down with it.
            AlminLog.warn("[almin] web dashboard snapshot failed: {}", e.toString());
        }
    }

    // ---------- routes ----------

    private void handleRoot(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain; charset=utf-8", "Method not allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/favicon.ico")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!path.equals("/")) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            String supplied = suppliedToken(ex);
            if (!tokenMatches(supplied)) {
                send(ex, 401, "text/html; charset=utf-8", LOGIN_HTML);
                return;
            }
            // Remember the token so links and refreshes don't need it in the URL.
            ex.getResponseHeaders().add("Set-Cookie",
                COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            send(ex, 200, "text/html; charset=utf-8", PAGE_HTML);
        } finally {
            ex.close();
        }
    }

    private void handleState(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, "application/json", "{\"error\":\"method\"}");
                return;
            }
            if (!tokenMatches(suppliedToken(ex))) {
                send(ex, 401, "application/json", "{\"error\":\"unauthorised\"}");
                return;
            }
            send(ex, 200, "application/json; charset=utf-8", snapshotJson);
        } finally {
            ex.close();
        }
    }

    // ---------- auth ----------

    /** The token from the query string, else the cookie, else null. */
    private String suppliedToken(HttpExchange ex) {
        URI uri = ex.getRequestURI();
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals("token")) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        Map<String, List<String>> headers = ex.getRequestHeaders();
        List<String> cookies = headers.get("Cookie");
        if (cookies == null) return null;
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String c = part.trim();
                if (c.startsWith(COOKIE + "=")) return c.substring(COOKIE.length() + 1);
            }
        }
        return null;
    }

    /** Constant-time comparison, so the token can't be guessed a byte at a time. */
    private boolean tokenMatches(String supplied) {
        if (supplied == null) return false;
        byte[] a = supplied.getBytes(StandardCharsets.UTF_8);
        byte[] b = token.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        // Nothing here is meant to be framed or indexed.
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------- pages ----------

    private static final String LOGIN_HTML = """
        <!doctype html><meta charset="utf-8"><title>Almin</title>
        <style>
          body{background:#14161a;color:#e6e6e6;font:15px/1.5 system-ui,sans-serif;
               display:grid;place-items:center;height:100vh;margin:0}
          .box{background:#1c1f26;border:1px solid #2b3039;border-radius:10px;padding:28px 32px;max-width:420px}
          h1{margin:0 0 8px;font-size:18px;color:#ffab33}
          p{margin:8px 0 0;color:#9aa3af}
          code{background:#0f1115;padding:2px 6px;border-radius:4px;color:#d8dee9}
        </style>
        <div class="box">
          <h1>Access token required</h1>
          <p>Open this page with <code>?token=…</code> in the address.</p>
          <p>The token is in <code>config/almin/config.json</code> as
             <code>web-ui-token</code>, or run <code>/almin op web</code> in game.</p>
        </div>
        """;

    private static final String PAGE_HTML = """
        <!doctype html><meta charset="utf-8"><title>Almin</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          :root{--bg:#14161a;--card:#1c1f26;--line:#2b3039;--dim:#9aa3af;--fg:#e6e6e6;--accent:#ffab33}
          *{box-sizing:border-box}
          body{background:var(--bg);color:var(--fg);font:14px/1.55 system-ui,-apple-system,sans-serif;margin:0}
          header{display:flex;align-items:center;gap:16px;padding:14px 20px;border-bottom:1px solid var(--line)}
          h1{margin:0;font-size:16px;color:var(--accent);letter-spacing:.3px}
          .age{margin-left:auto;color:var(--dim);font-size:12px}
          nav{display:flex;gap:4px;padding:10px 20px 0}
          nav button{background:none;border:1px solid transparent;color:var(--dim);padding:6px 14px;
                     border-radius:6px 6px 0 0;cursor:pointer;font:inherit}
          nav button.on{color:var(--fg);background:var(--card);border-color:var(--line);border-bottom-color:var(--card)}
          main{padding:0 20px 28px}
          .panel{display:none}.panel.on{display:block}
          .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:14px;margin-top:14px}
          section{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
          h2{margin:0 0 10px;font-size:13px;text-transform:uppercase;letter-spacing:.8px;color:var(--accent)}
          .row{display:flex;gap:12px;padding:3px 0;border-bottom:1px solid rgba(255,255,255,.04)}
          .row:last-child{border-bottom:0}
          .k{color:var(--dim);white-space:nowrap}
          .v{margin-left:auto;text-align:right;font-variant-numeric:tabular-nums}
          .note{color:#6b7280;font-style:italic;padding:3px 0}
          pre{background:#0f1115;border:1px solid var(--line);border-radius:10px;margin-top:14px;
              padding:12px 14px;max-height:72vh;overflow:auto;white-space:pre-wrap;word-break:break-word;
              font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;color:#c9d1d9}
          .warn{color:#ffcc55}.err{color:#ff6655}
        </style>
        <header>
          <h1>Almin</h1>
          <span class="age" id="age">connecting…</span>
        </header>
        <nav>
          <button id="tab-dash" class="on">Dashboard</button>
          <button id="tab-log">Console</button>
        </nav>
        <main>
          <div class="panel on" id="p-dash"><div class="grid" id="grid"></div></div>
          <div class="panel" id="p-log"><pre id="log"></pre></div>
        </main>
        <script>
        const $ = id => document.getElementById(id);
        let stuck = true;
        function tab(which){
          for (const n of ['dash','log']) {
            $('tab-'+n).classList.toggle('on', n===which);
            $('p-'+n).classList.toggle('on', n===which);
          }
        }
        $('tab-dash').onclick = () => tab('dash');
        $('tab-log').onclick  = () => { tab('log'); stuck = true; draw(last); };
        // Stop auto-scrolling once the reader scrolls up to look at something.
        $('log').addEventListener('scroll', () => {
          const el = $('log');
          stuck = el.scrollTop + el.clientHeight >= el.scrollHeight - 24;
        });
        let last = null;
        function esc(s){ return s.replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])); }
        function draw(d){
          if (!d) return;
          last = d;
          const grid = $('grid');
          grid.innerHTML = '';
          let sec = null;
          for (const r of d.rows) {
            if (r.kind === 0) {
              sec = document.createElement('section');
              const h = document.createElement('h2');
              h.textContent = r.label;
              sec.appendChild(h);
              grid.appendChild(sec);
            } else if (sec) {
              const div = document.createElement('div');
              if (r.kind === 2) {
                div.className = 'note';
                div.textContent = r.label;
              } else {
                div.className = 'row';
                const k = document.createElement('span'); k.className = 'k'; k.textContent = r.label;
                const v = document.createElement('span'); v.className = 'v'; v.textContent = r.value;
                if (r.accent) v.style.color = r.accent;
                div.append(k, v);
              }
              sec.appendChild(div);
            }
          }
          const log = $('log');
          log.innerHTML = d.console.map(l => {
            const c = /\\/ERROR\\]| ERROR /.test(l) ? 'err' : /\\/WARN\\]| WARN /.test(l) ? 'warn' : '';
            return c ? '<span class="'+c+'">'+esc(l)+'</span>' : esc(l);
          }).join('\\n');
          if (stuck) log.scrollTop = log.scrollHeight;
          const secs = Math.max(0, Math.round((Date.now() - d.generated)/1000));
          $('age').textContent = 'updated ' + (secs < 2 ? 'just now' : secs + 's ago');
        }
        async function poll(){
          try {
            const r = await fetch('/api/state', {credentials:'same-origin'});
            if (r.status === 401) { $('age').textContent = 'token rejected — reopen with ?token=…'; return; }
            draw(await r.json());
          } catch (e) {
            $('age').textContent = 'server unreachable';
          }
        }
        poll();
        setInterval(poll, 3000);
        </script>
        """;
}
