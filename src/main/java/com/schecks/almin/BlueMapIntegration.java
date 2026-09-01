package com.schecks.almin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deliberately narrow boundary between Almin and an optional BlueMap install.
 *
 * <p>There is no BlueMap dependency here, reflective or otherwise. Almin finds the
 * separately installed Fabric jar by its metadata, configures BlueMap's own web
 * server on loopback, and writes an Almin-owned browser bridge into BlueMap's
 * webroot. The two running mods meet only through HTTP and BlueMap's documented
 * custom-script hook. This keeps BlueMap optional and keeps its classes and assets
 * out of Almin's jar.
 */
final class BlueMapIntegration {
    static final String MOD_ID = "bluemap";
    static final String BRIDGE_URL = "js/almin-bridge.js";
    private static final int DEFAULT_PORT = 8100;
    private static final int LAST_AUTO_PORT = 8199;
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(900);
    private static final long PROCESS_STARTED = java.lang.management.ManagementFactory
        .getRuntimeMXBean().getStartTime();
    private static final Pattern PORT = Pattern.compile("(?m)^\\s*port\\s*:\\s*(\\d+)\\s*$");
    private static final Pattern IP = Pattern.compile("(?m)^\\s*ip\\s*:\\s*[\"']?([^\"'#\\s]+)");
    private static final Pattern WEBROOT = Pattern.compile(
        "(?m)^\\s*webroot\\s*:\\s*(?:\"([^\"]+)\"|'([^']+)'|([^#\\s]+))\\s*(?:#.*)?$");
    private static final Pattern ACCEPT_DOWNLOAD = Pattern.compile(
        "(?mi)^\\s*accept-download\\s*:\\s*(true|false)\\s*(?:#.*)?$");
    private static final Pattern SCRIPTS = Pattern.compile("(?s)(scripts\\s*:\\s*\\[)(.*?)(\\])");
    private static final HttpClient PROBE = HttpClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private static volatile int lastProbePort;
    private static volatile long lastProbeAt;
    private static volatile boolean lastProbeReady;
    private static volatile long lastPurgeAt;

    record Status(boolean installed, boolean enabled, boolean loaded,
                  boolean configured, boolean ready, boolean restartRequired,
                  boolean downloadAccepted, int port, String version, String message) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("installed", installed);
            o.addProperty("enabled", enabled);
            o.addProperty("loaded", loaded);
            o.addProperty("configured", configured);
            o.addProperty("ready", ready);
            o.addProperty("restartRequired", restartRequired);
            o.addProperty("downloadAccepted", downloadAccepted);
            o.addProperty("port", port);
            o.addProperty("version", version == null ? "" : version);
            o.addProperty("message", message == null ? "" : message);
            o.addProperty("path", "/bluemap/");
            return o;
        }
    }

    record Result(boolean ok, boolean restartRequired, int port, String message) {
        static Result fail(String message) { return new Result(false, false, 0, message); }
    }

    private record Config(int port, boolean loopback, boolean script, boolean bridge,
                          boolean downloadAccepted,
                          boolean changedThisRun, Path webroot) {}

    private BlueMapIntegration() {}

    static Status status(MinecraftServer server, int panelPort) {
        Path root = server == null ? null : server.getServerDirectory();
        return status(root, panelPort, loaded(), true);
    }

    /** Path-shaped version used by the harness without a running game. */
    static Status status(Path root, int panelPort, boolean loaded, boolean doProbe) {
        ServerMods.Installed found = null;
        if (root != null) {
            for (ServerMods.Installed mod : ServerMods.list(root.resolve("mods"))) {
                if (MOD_ID.equals(mod.modId())) { found = mod; break; }
            }
        }
        boolean installed = found != null;
        boolean enabled = installed && found.enabled();
        Config cfg = readConfig(root);
        boolean configured = cfg.loopback && cfg.script && cfg.bridge
            && cfg.port > 0 && cfg.port != panelPort;
        boolean ready = enabled && loaded && configured && cfg.downloadAccepted
            && !cfg.changedThisRun
            && (!doProbe || probeCached(cfg.port));
        boolean restart = installed && enabled && cfg.downloadAccepted
            && (!loaded || !configured || cfg.changedThisRun);
        String message;
        if (!installed) message = "Install BlueMap to use the 3D world map.";
        else if (!enabled) message = "BlueMap is turned off in mods/.";
        else if (!cfg.downloadAccepted) message =
            "BlueMap cannot start until you set accept-download: true in "
                + "config/bluemap/core.conf, then restart. BlueMap requires this for its "
                + "Minecraft client-resource download.";
        else if (!loaded) message = "BlueMap is installed and will load at the next server start.";
        else if (!configured) message = cfg.port == panelPort
            ? "BlueMap and the Almin panel are configured for the same port."
            : "Connect BlueMap to Almin to keep its web server private.";
        else if (cfg.changedThisRun) message =
            "BlueMap is configured. Restart once to apply its private loopback web server.";
        else if (!ready) message = "BlueMap is loaded, but its web app is not answering yet.";
        else message = "BlueMap " + (found.version().isBlank() ? "" : found.version() + " ")
            + "is connected through Almin.";
        return new Status(installed, enabled, loaded, configured, ready, restart,
            cfg.downloadAccepted,
            cfg.port, found == null ? "" : found.version(), message);
    }

    /**
     * Creates or amends only BlueMap-owned configuration and the Almin bridge.
     * Existing settings are preserved; the three values Almin needs are updated
     * in place rather than replacing the files.
     */
    static Result configure(MinecraftServer server, int panelPort) {
        if (server == null) return Result.fail("The Minecraft server is not running.");
        return configure(server.getServerDirectory(), panelPort);
    }

    static Result configure(Path root, int panelPort) {
        if (root == null) return Result.fail("The server folder is not available.");
        try {
            Path configDir = root.resolve("config").resolve("bluemap");
            Files.createDirectories(configDir);
            Path serverConfig = configDir.resolve("webserver.conf");
            String serverText = Files.exists(serverConfig)
                ? Files.readString(serverConfig, StandardCharsets.UTF_8)
                : "# BlueMap web server; managed only where marked by Almin.\n"
                    + "enabled: true\nwebroot: \"bluemap/web\"\n"
                    + "sse-enabled: true\n";

            int current = scalarInt(serverText, PORT, 0);
            boolean currentWorks = current > 0 && current != panelPort
                && (probe(current) || portAvailable(current));
            int chosen = currentWorks ? current : choosePort(panelPort);
            if (chosen <= 0) return Result.fail("No free loopback port from 8100 to 8199.");
            serverText = setScalar(serverText, "ip", "\"127.0.0.1\"");
            serverText = setScalar(serverText, "port", String.valueOf(chosen));
            writeAtomic(serverConfig, serverText);

            Path appConfig = configDir.resolve("webapp.conf");
            String appText = Files.exists(appConfig)
                ? Files.readString(appConfig, StandardCharsets.UTF_8)
                : "# BlueMap web app with Almin's optional activity bridge.\n"
                    + "enabled: true\nwebroot: \"bluemap/web\"\n";
            appText = addScript(appText);
            writeAtomic(appConfig, appText);

            Path webroot = webroot(root, appText);
            Path bridge = safeBridge(root, webroot);
            if (bridge == null) return Result.fail("BlueMap's webroot is outside this server instance.");
            Files.createDirectories(bridge.getParent());
            writeAtomic(bridge, BRIDGE);
            patchGeneratedSettings(webroot.resolve("settings.json"));
            lastProbeAt = 0;

            boolean wasLoaded = loaded();
            String when = wasLoaded
                ? " Restart once so BlueMap binds loopback with this configuration."
                : " It will be ready after the next server start.";
            AlminLog.info("[almin] BlueMap web bridge configured on 127.0.0.1:{}", chosen);
            return new Result(true, true, chosen,
                "BlueMap is connected to Almin on a private loopback port." + when);
        } catch (IOException | RuntimeException e) {
            return Result.fail("Could not configure BlueMap: " + e.getMessage());
        }
    }

    /** Records BlueMap's required client-resource-download acceptance. */
    static Result acceptDownload(MinecraftServer server) {
        if (server == null) return Result.fail("The Minecraft server is not running.");
        return acceptDownload(server.getServerDirectory());
    }

    static Result acceptDownload(Path root) {
        if (root == null) return Result.fail("The server folder is not available.");
        try {
            Path core = root.resolve("config").resolve("bluemap").resolve("core.conf");
            String text = Files.exists(core)
                ? Files.readString(core, StandardCharsets.UTF_8)
                : "# BlueMap client-resource download accepted from the Almin panel.\n";
            writeAtomic(core, setScalar(text, "accept-download", "true"));
            lastProbeAt = 0;
            return new Result(true, true, 0,
                "BlueMap may download the Minecraft client resources it needs. Restart once to start the renderer.");
        } catch (IOException | RuntimeException e) {
            return Result.fail("Could not update BlueMap's download setting: " + e.getMessage());
        }
    }

    /**
     * Purges every configured map through BlueMap's documented command. This
     * works with file and database storages alike and leaves the Minecraft
     * world, BlueMap configuration, and Almin data untouched.
     */
    static synchronized Result reset(MinecraftServer server) {
        if (server == null) return Result.fail("The Minecraft server is not running.");
        if (!loaded()) return Result.fail("BlueMap is not loaded.");
        long now = System.currentTimeMillis();
        if (now - lastPurgeAt < 30_000) {
            return Result.fail("A BlueMap reset was already requested less than 30 seconds ago.");
        }
        List<String> maps = mapIds(server.getServerDirectory());
        if (maps.isEmpty()) {
            return Result.fail("BlueMap has no map configs under config/bluemap/maps/.");
        }
        // Arm the repeat guard before the first command. If one later map
        // fails, the maps already accepted must not be purged a second time by
        // an immediate retry.
        lastPurgeAt = now;
        int queued = 0;
        try {
            for (String map : maps) {
                String command = "bluemap purge " + StringArgumentType.escapeIfRequired(map);
                server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack(), command);
                queued++;
            }
        } catch (RuntimeException e) {
            return Result.fail("BlueMap accepted " + queued + " of " + maps.size()
                + " purge requests before failing: " + e.getMessage());
        }
        AlminLog.warn("[almin] requested BlueMap purge for {}", String.join(", ", maps));
        return new Result(true, false, 0, "Reset requested for " + queued
            + (queued == 1 ? " BlueMap map" : " BlueMap maps")
            + ". BlueMap will re-render them unless they are frozen.");
    }

    /** Map ids are the names of BlueMap's map configs without .conf. */
    static List<String> mapIds(Path root) {
        if (root == null) return List.of();
        Path maps = root.resolve("config").resolve("bluemap").resolve("maps");
        if (!Files.isDirectory(maps)) return List.of();
        List<String> ids = new ArrayList<>();
        try (var files = Files.list(maps)) {
            files.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".conf") && name.length() > 5)
                .map(name -> name.substring(0, name.length() - 5))
                .sorted(Comparator.naturalOrder())
                .limit(128)
                .forEach(ids::add);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return List.copyOf(ids);
    }

    private static boolean loaded() {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID).isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Config readConfig(Path root) {
        if (root == null) return new Config(DEFAULT_PORT, false, false, false, false, false, null);
        Path configDir = root.resolve("config").resolve("bluemap");
        Path serverFile = configDir.resolve("webserver.conf");
        Path appFile = configDir.resolve("webapp.conf");
        Path coreFile = configDir.resolve("core.conf");
        try {
            String serverText = Files.exists(serverFile) ? Files.readString(serverFile) : "";
            String appText = Files.exists(appFile) ? Files.readString(appFile) : "";
            String coreText = Files.exists(coreFile) ? Files.readString(coreFile) : "";
            int port = scalarInt(serverText, PORT, DEFAULT_PORT);
            Matcher ip = IP.matcher(serverText);
            String bind = ip.find() ? ip.group(1).trim().toLowerCase(Locale.ROOT) : "0.0.0.0";
            boolean loopback = bind.equals("127.0.0.1") || bind.equals("::1")
                || bind.equals("localhost");
            Path webroot = webroot(root, appText);
            Path bridge = safeBridge(root, webroot);
            Matcher accepted = ACCEPT_DOWNLOAD.matcher(coreText);
            boolean downloadAccepted = accepted.find()
                && Boolean.parseBoolean(accepted.group(1).toLowerCase(Locale.ROOT));
            long changed = Math.max(modified(coreFile),
                Math.max(modified(serverFile), Math.max(modified(appFile), modified(bridge))));
            return new Config(port, loopback, appText.contains(BRIDGE_URL),
                bridge != null && Files.isRegularFile(bridge), downloadAccepted,
                changed > PROCESS_STARTED, webroot);
        } catch (IOException | RuntimeException e) {
            return new Config(DEFAULT_PORT, false, false, false, false, false, null);
        }
    }

    private static long modified(Path path) {
        if (path == null) return 0;
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException e) { return 0; }
    }

    private static int scalarInt(String text, Pattern pattern, int fallback) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return fallback;
        try { return Integer.parseInt(m.group(1)); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String setScalar(String text, String key, String value) {
        Pattern p = Pattern.compile("(?m)^\\s*#?\\s*" + Pattern.quote(key)
            + "\\s*:\\s*[^\\r\\n]*$");
        Matcher m = p.matcher(text);
        if (m.find()) return m.replaceFirst(Matcher.quoteReplacement(key + ": " + value));
        return text + (text.endsWith("\n") ? "" : "\n") + key + ": " + value + "\n";
    }

    private static String addScript(String text) {
        if (text.contains(BRIDGE_URL)) return text;
        Matcher m = SCRIPTS.matcher(text);
        if (m.find()) {
            String middle = m.group(2).trim();
            String added = middle.isEmpty() ? "\n  \"" + BRIDGE_URL + "\"\n"
                : "\n  \"" + BRIDGE_URL + "\",\n  " + middle + "\n";
            return m.replaceFirst(Matcher.quoteReplacement(m.group(1) + added + m.group(3)));
        }
        return text + (text.endsWith("\n") ? "" : "\n")
            + "scripts: [\n  \"" + BRIDGE_URL + "\"\n]\n";
    }

    private static Path webroot(Path root, String appText) {
        String relative = "bluemap/web";
        Matcher m = WEBROOT.matcher(appText);
        if (m.find()) {
            String configured = m.group(1) != null ? m.group(1)
                : m.group(2) != null ? m.group(2) : m.group(3);
            if (!configured.contains("${")) relative = configured.trim();
        }
        Path p = root.resolve(relative).toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        return p.startsWith(base) && insideRealRoot(base, p) ? p : null;
    }

    private static Path safeBridge(Path root, Path webroot) {
        if (webroot == null) return null;
        Path bridge = webroot.resolve(BRIDGE_URL).toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        return bridge.startsWith(webroot.toAbsolutePath().normalize())
            && insideRealRoot(base, bridge) ? bridge : null;
    }

    /** Refuses an existing symlink that would make an apparently local path escape. */
    private static boolean insideRealRoot(Path base, Path target) {
        try {
            Path realBase = base.toRealPath();
            Path existing = target;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            return existing != null && existing.toRealPath().startsWith(realBase);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static int choosePort(int panelPort) {
        for (int port = DEFAULT_PORT; port <= LAST_AUTO_PORT; port++) {
            if (port == panelPort) continue;
            if (portAvailable(port)) return port;
        }
        return 0;
    }

    private static boolean portAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException ignored) {
            // This instance shares a host politely.
            return false;
        }
    }

    private static boolean probe(int port) {
        if (port <= 0 || port > 65535) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/settings.json"))
                .timeout(PROBE_TIMEOUT).GET().build();
            HttpResponse<String> response = PROBE.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 && response.body().contains("\"maps\"")
                && response.body().contains("\"version\"");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static synchronized boolean probeCached(int port) {
        long now = System.currentTimeMillis();
        if (port == lastProbePort && now - lastProbeAt < 2000) return lastProbeReady;
        lastProbePort = port;
        lastProbeReady = probe(port);
        lastProbeAt = now;
        return lastProbeReady;
    }

    private static void patchGeneratedSettings(Path settings) {
        if (!Files.isRegularFile(settings)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
            JsonArray scripts = root.has("scripts") && root.get("scripts").isJsonArray()
                ? root.getAsJsonArray("scripts") : new JsonArray();
            boolean present = false;
            for (var script : scripts) if (BRIDGE_URL.equals(script.getAsString())) present = true;
            if (!present) scripts.add(BRIDGE_URL);
            root.add("scripts", scripts);
            writeAtomic(settings, root.toString());
        } catch (IOException | RuntimeException e) {
            AlminLog.warn("[almin] could not update BlueMap settings.json: {}", e.getMessage());
        }
    }

    private static void writeAtomic(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".almin-bluemap-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Almin-owned code loaded by BlueMap's supported custom-script hook.
     * It consumes plain activity-state messages; it contains no BlueMap source.
     */
    static final String BRIDGE = """
        (() => {
          'use strict';
          const SOURCE='almin-activity-v1';
          let state=null, root=null, lastFocus=-1, changing=false;

          const esc=s=>String(s==null?'':s).replace(/[&<>\"']/g,c=>
            ({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c]));
          const rgb=h=>{
            try {
              const c=new window.BlueMap.Three.Color(h||'#9aa3ae');
              return {r:Math.round(c.r*255),g:Math.round(c.g*255),b:Math.round(c.b*255),a:.72};
            } catch(e) {}
            const m=/^#?([0-9a-f]{6})$/i.exec(h||'');
            const n=m?parseInt(m[1],16):0x9aa3ae;
            return {r:(n>>16)&255,g:(n>>8)&255,b:n&255,a:0.72};
          };
          const markerHtml=m=>'<button class="almin-mark '+esc(m.shape||'dot')+'" '+
            'data-almin-kind="'+esc(m.kind||'action')+'" data-almin-id="'+esc(m.id)+'" '+
            'style="--almin-color:'+esc(m.color||'#9aa3ae')+';--almin-size:'+
            esc(m.size||1)+';opacity:'+(m.opacity==null?1:m.opacity)+'" title="'+
            esc(m.title||'')+'">'+esc(m.text||'')+'</button>';
          const headHtml=m=>'<button class="almin-head" data-almin-kind="player" '+
            'data-almin-id="'+esc(m.id)+'" style="--almin-color:'+esc(m.color)+';--almin-size:'+
            esc(m.size||1)+'" title="'+esc(m.title||'')+'">'+
            (m.icon?'<img src="'+esc(m.icon)+'" alt="">':'')+'<span>'+esc(m.text)+'</span></button>';

          function ensureRoot(){
            const app=window.bluemap, api=window.BlueMap;
            if(!app||!api||!api.MarkerSet||!app.popupMarkerSet) return false;
            if(root && root.parent) return true;
            root=new api.MarkerSet('almin-activity');
            root.data.label='Almin activity'; root.data.toggleable=true;
            app.popupMarkerSet.add(root);
            return true;
          }

          function lineData(m){
            return {type:'line',position:m.points[0]||{x:0,y:0,z:0},label:m.label||'',
              detail:m.detail||'',listed:false,line:m.points,depthTest:false,
              lineWidth:m.width||2,lineColor:{...rgb(m.color),a:m.opacity==null?.9:m.opacity},
              minDistance:0,maxDistance:Number.MAX_VALUE};
          }
          function boxData(m){
            const c=rgb(m.color), x=m.x, z=m.z;
            const opacity=Math.max(0,Math.min(1,m.opacity==null?1:m.opacity));
            return {type:'extrude',position:{x:x+.5,y:m.y,z:z+.5},label:m.label||'',
              detail:m.detail||'',listed:false,shape:[{x:x+.02,z:z+.02},{x:x+.98,z:z+.02},
              {x:x+.98,z:z+.98},{x:x+.02,z:z+.98}],holes:[],shapeMinY:m.y+.02,
              shapeMaxY:m.y+.98,depthTest:false,lineWidth:2,
              lineColor:{...c,a:.95*opacity},
              fillColor:{...c,a:(m.fill==null?.34:m.fill)*opacity},
              minDistance:0,maxDistance:Number.MAX_VALUE};
          }
          function htmlData(m,html){
            return {type:'html',position:{x:m.x,y:m.y,z:m.z},label:m.title||'',listed:false,
              anchor:{x:0,y:0},html:html,classes:['almin-html'],minDistance:0,
              maxDistance:Number.MAX_VALUE};
          }

          async function chooseMap(dim){
            const app=window.bluemap; if(!app||!app.maps||!app.maps.length) return;
            const d=String(dim||'').toLowerCase();
            const score=map=>{
              const id=(map.data.id+' '+map.data.name).toLowerCase();
              if(d.includes('nether')) return id.includes('nether')?20:-10;
              if(d.includes('end')) return id.includes('end')?20:-10;
              return (!id.includes('nether')&&!id.includes('end'))?10:0;
            };
            const map=[...app.maps].sort((a,b)=>score(b)-score(a))[0];
            if(map && (!app.mapViewer.map||app.mapViewer.map.data.id!==map.data.id)){
              changing=true;
              try { await app.switchMap(map.data.id,false); }
              finally { changing=false; }
            }
          }

          async function render(){
            if(!state||!ensureRoot()) return;
            await chooseMap(state.dimension);
            if(!root||!root.parent) { ensureRoot(); }
            if(!root) return;
            const sets={}, actions={}, tracks={}, scenes={}, grid={};
            for(const m of state.markers||[]) actions[m.id]=htmlData(m,markerHtml(m));
            for(const m of state.players||[]) tracks[m.id]=htmlData(m,headHtml(m));
            for(const m of state.lines||[]) tracks[m.id]=lineData(m);
            for(const m of state.scenes||[]){
              scenes[m.id]=m.type==='box'?boxData(m):htmlData(m,markerHtml(m));
            }
            for(const m of state.grid||[]) grid[m.id]=m.type==='label'
              ? htmlData(m,markerHtml({...m,kind:'grid',shape:'gridlabel'})) : lineData(m);
            sets['almin-actions']={label:'Activity',toggleable:true,sorting:-50,markers:actions};
            sets['almin-tracks']={label:'Player paths',toggleable:true,sorting:-49,markers:tracks};
            sets['almin-scenes']={label:'3D events',toggleable:true,sorting:-48,markers:scenes};
            sets['almin-grid']={label:'Coordinate grid',toggleable:true,sorting:-47,markers:grid};
            root.updateMarkerSetsFromData(sets);
            const canvas=window.bluemap.mapViewer.renderer.domElement;
            if(canvas) canvas.style.filter='brightness('+(1-Math.min(.55,state.darkness||0))+')';
            if(state.focus && state.focus.nonce!==lastFocus){
              lastFocus=state.focus.nonce;
              const c=window.bluemap.mapViewer.controlsManager;
              c.position.set(state.focus.x,state.focus.y||0,state.focus.z);
              c.distance=Math.max(20,state.focus.distance||140);
              window.bluemap.setPerspectiveView(0,20);
              window.bluemap.mapViewer.updateLoadedMapArea();
              window.bluemap.mapViewer.redraw();
            }
          }

          window.addEventListener('message',e=>{
            if(e.source!==parent||e.origin!==location.origin||
               !e.data||e.data.source!==SOURCE) return;
            if(e.data.type==='state'){ state=e.data.state; render().catch(console.error); }
          });
          document.addEventListener('click',e=>{
            const target=e.target.closest&&e.target.closest('[data-almin-id]');
            if(!target) return;
            e.preventDefault(); e.stopPropagation();
            parent.postMessage({source:SOURCE,type:'select',kind:target.dataset.alminKind,
              id:target.dataset.alminId},location.origin);
          },true);
          let cameraTimer=0;
          const camera=()=>{
            if(changing||!window.bluemap||!window.bluemap.mapViewer.map) return;
            clearTimeout(cameraTimer);
            cameraTimer=setTimeout(()=>{
              const c=window.bluemap.mapViewer.controlsManager, map=window.bluemap.mapViewer.map;
              parent.postMessage({source:SOURCE,type:'camera',x:c.position.x,y:c.position.y,
                z:c.position.z,distance:c.distance,map:map.data.id},location.origin);
            },120);
          };
          const ready=()=>{
            if(!ensureRoot()) return setTimeout(ready,100);
            window.bluemap.events.addEventListener('bluemapCameraMoved',camera);
            window.bluemap.events.addEventListener('bluemapMapChanged',()=>{
              ensureRoot(); render().catch(console.error); camera();
            });
            window.bluemap.events.addEventListener('bluemapMapInteraction',e=>{
              const p=e.detail&&e.detail.hit&&e.detail.hit.point;
              if(!p) return;
              parent.postMessage({source:SOURCE,type:'worldclick',x:Math.floor(p.x),
                y:Math.floor(p.y-.01),z:Math.floor(p.z)},location.origin);
            });
            injectStyle();
            parent.postMessage({source:SOURCE,type:'ready'},location.origin);
            camera(); render().catch(console.error);
          };
          function injectStyle(){
            if(document.getElementById('almin-bridge-style')) return;
            const s=document.createElement('style'); s.id='almin-bridge-style';
            s.textContent='.almin-html{pointer-events:auto}.almin-mark,.almin-head{pointer-events:auto;'+
              'border:2px solid #0b0d11;color:#fff;background:var(--almin-color);box-shadow:0 2px 8px #000b;'+
              'cursor:pointer;font:700 11px system-ui;transform:scale(var(--almin-size));transform-origin:center}'+
              '.almin-mark{min-width:15px;height:15px;border-radius:50%;padding:0 3px}.almin-mark.cluster{'+
              'min-width:25px;height:21px;border-radius:7px}.almin-mark.scene{width:auto;height:23px;border-radius:5px;'+
              'padding:0 6px;background:#ffab33;color:#14100a}.almin-mark.gridlabel{width:auto;height:auto;'+
              'border:0;background:#10141bc9;color:#d9e0e8;border-radius:3px;padding:1px 3px;font-size:9px}'+
              '.almin-head{display:flex;align-items:center;gap:4px;'+
              'border-radius:5px;padding:2px 5px}.almin-head img{width:20px;height:20px;image-rendering:pixelated}';
            document.head.appendChild(s);
          }
          ready();
        })();
        """;
}
