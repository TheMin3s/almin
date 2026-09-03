import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Optional BlueMap integration: separation, configuration and browser bridge. */
public class BlueMapTests {
    static int failures;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + label
            + (ok ? "" : " -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("almin-bluemap-test-");
        try {
            Files.createDirectories(root.resolve("mods"));
            fakeBlueMap(root.resolve("mods/bluemap-5.23.jar"));
            Path cfg = root.resolve("config/bluemap");
            Files.createDirectories(cfg);
            Files.writeString(cfg.resolve("webapp.conf"),
                "default-to-flat-view: true\n" +
                "scripts: [\n  \"js/something-else.js\"\n]\n");
            Files.writeString(cfg.resolve("webserver.conf"),
                "enabled: true\nport: 8123\nsse-enabled: true\n");

            Class<?> type = Class.forName("com.schecks.almin.BlueMapIntegration");
            Method configure = type.getDeclaredMethod("configure", Path.class, int.class);
            configure.setAccessible(true);
            Object result = configure.invoke(null, root, 8123);
            Method ok = result.getClass().getDeclaredMethod("ok"); ok.setAccessible(true);
            check("BlueMap can be configured without loading its classes",
                (boolean) ok.invoke(result), result.toString());

            String server = Files.readString(cfg.resolve("webserver.conf"));
            String app = Files.readString(cfg.resolve("webapp.conf"));
            Path bridge = root.resolve("bluemap/web/js/almin-bridge.js");
            check("the BlueMap server is loopback-only and cannot take the panel port",
                server.contains("ip: \"127.0.0.1\"") && !server.contains("port: 8123"), server);
            check("existing BlueMap web settings and scripts survive",
                app.contains("default-to-flat-view: true")
                    && app.contains("js/something-else.js")
                    && app.contains("js/almin-bridge.js"), app);
            check("the separately served bridge is written into BlueMap's webroot",
                Files.isRegularFile(bridge)
                    && Files.readString(bridge).contains("almin-activity-v1")
                    && Files.readString(bridge).contains("window.BlueMap")
                    && Files.readString(bridge).contains("e.source!==parent"),
                Files.exists(bridge) ? "bridge incomplete" : "bridge missing");

            // BlueMap keeps its own heads on the map, at wherever everybody is
            // standing this second. Scrubbed back an hour that is a second
            // copy of every player, in the wrong place, beside the one Almin
            // drew where they actually were. Turned off through whichever
            // handle this BlueMap version has, and hidden in CSS as well,
            // because none of it is an API Almin is entitled to depend on.
            String js = Files.readString(bridge);
            check("BlueMap's own live player heads are turned off in playback",
                js.contains("state.livePlayers!==false")
                    && js.contains("playerMarkerManager")
                    && js.contains("almin-no-live-players"), "bridge left them on");
            String turn = js.substring(js.indexOf("function livePlayers"),
                js.indexOf("async function render"));
            check("...without reaching into BlueMap unguarded",
                turn.contains("try {") && turn.contains("catch(e) {}"),
                "the reach into BlueMap's internals is not guarded");
            check("...and the CSS half runs even before BlueMap is up",
                turn.indexOf("almin-no-live-players") < turn.indexOf("if(!app) return"),
                "nothing happens at all until BlueMap answers");

            Method status = type.getDeclaredMethod(
                "status", Path.class, int.class, boolean.class, boolean.class);
            status.setAccessible(true);
            Object stopped = status.invoke(null, root, 8123, false, false);
            check("an installed jar is distinguished from a loaded renderer",
                bool(stopped, "installed") && bool(stopped, "configured")
                    && !bool(stopped, "loaded") && !bool(stopped, "restartRequired")
                    && stopped.toString().contains("accept-download"),
                stopped.toString());

            Object gated = status.invoke(null, root, 8123, true, false);
            check("BlueMap's required client-resource acceptance is diagnosed",
                !bool(gated, "ready") && !bool(gated, "downloadAccepted")
                    && gated.toString().contains("accept-download"), gated.toString());
            Method accept = type.getDeclaredMethod("acceptDownload", Path.class);
            accept.setAccessible(true);
            Object accepted = accept.invoke(null, root);
            String core = Files.readString(cfg.resolve("core.conf"));
            check("the panel can record explicit BlueMap download acceptance",
                (boolean) ok.invoke(accepted) && core.contains("accept-download: true"), core);

            // Make the already-written configuration look as it will after a
            // process restart. A loaded-but-unprobed BlueMap is then eligible.
            FileTime old = FileTime.fromMillis(1);
            Files.setLastModifiedTime(cfg.resolve("webserver.conf"), old);
            Files.setLastModifiedTime(cfg.resolve("webapp.conf"), old);
            Files.setLastModifiedTime(cfg.resolve("core.conf"), old);
            Files.setLastModifiedTime(bridge, old);
            Object loaded = status.invoke(null, root, 8123, true, false);
            check("a loaded, previously secured install is ready for the proxy",
                bool(loaded, "ready") && bool(loaded, "downloadAccepted")
                    && !bool(loaded, "restartRequired"), loaded.toString());

            Path maps = cfg.resolve("maps");
            Files.createDirectories(maps);
            Files.writeString(maps.resolve("world.conf"), "world: world\n");
            Files.writeString(maps.resolve("world_nether.conf"), "world: world\n");
            Files.writeString(maps.resolve("ignore.txt"), "not a map\n");
            Files.createSymbolicLink(maps.resolve("linked.conf"), maps.resolve("world.conf"));
            Method mapIds = type.getDeclaredMethod("mapIds", Path.class);
            mapIds.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ids = (java.util.List<String>) mapIds.invoke(null, root);
            check("the reset discovers configured BlueMap ids without following links",
                ids.equals(java.util.List.of("world", "world_nether")), ids.toString());

            String integration = Files.readString(Path.of(
                "src/main/java/com/schecks/almin/BlueMapIntegration.java"));
            String proxy = Files.readString(Path.of(
                "src/main/java/com/schecks/almin/BlueMapProxy.java"));
            String web = Files.readString(Path.of(
                "src/main/java/com/schecks/almin/WebUi.java"));
            String page = Files.readString(Path.of(
                "src/main/java/com/schecks/almin/WebPage.java"));
            check("Almin does not import or reflect into BlueMap Java code",
                !integration.contains("import de.bluecolored")
                    && !integration.contains("Class.forName(\"de.bluecolored")
                    && !proxy.contains("de.bluecolored"), "BlueMap Java linkage found");
            check("every proxied tile is behind the Almin session",
                web.contains("/bluemap\", ui.guard(\"/bluemap\", ui::handleBlueMapProxy)")
                    && section(web, "private void handleBlueMapProxy", 500)
                        .contains("requireAuth(ex)"), "proxy route is public");
            check("download acceptance requires an explicit panel action",
                page.contains("Allow resource download")
                    && page.contains("action:'accept-download'")
                    && section(web, "private void handleBlueMap", 1600)
                        .contains("acceptDownload(server)"),
                "the acceptance action is missing from the authenticated BlueMap route");
            check("the authenticated reset uses BlueMap's supported purge command",
                page.contains("Reset BlueMap renders")
                    && page.contains("action:'reset'")
                    && page.contains("full re-render can use substantial NAS disk I/O")
                    && section(web, "private void handleBlueMap", 2200)
                        .contains("BlueMapIntegration.reset(server)")
                    && integration.contains("bluemap purge ")
                    && integration.contains("performPrefixedCommand"),
                "reset bypasses BlueMap or is missing its load warning");
            check("BlueMap streams cannot starve the four panel workers",
                web.contains("newFixedThreadPool(16") && web.contains("Almin-BlueMap")
                    && web.contains("blueMapPool().execute"), "no separate streaming pool");
            check("the world renderer carries the map controls and real 3D scenes",
                page.contains("function blueMapPayload")
                    && page.contains("type:'box'")
                    && page.contains("function inspectBlueWorld")
                    && page.contains("Legacy 2D")
                    && page.contains("mapOpts.sceneEvents"), "browser feature bridge incomplete");
            check("the activity settings stay scrollable inside the BlueMap frame",
                page.contains(".bluemapwrap .mapopts{max-height")
                    && page.contains("overflow-y:auto")
                    && page.contains("class=\"layers\"")
                    && page.contains("o-blockmins"),
                "the settings card can still be clipped by the map");
            check("recent BlueMap block edits are automatic and independently timed",
                page.contains("function blockChangeOpacity")
                    && page.contains("kind:opts.recent?'block-change':'block'")
                    && page.contains("'#48df6b':'#ff565d'")
                    && page.contains("mapOpts.blocks&&blueCamera")
                    && page.contains(".slice(0,500)")
                    && page.contains("id:'recent-block-'+a.x")
                    && integration.contains(".95*opacity"),
                "recent block outline fading is incomplete");
            Class<?> config = Class.forName("com.schecks.almin.AlminConfig");
            var constructor = config.getDeclaredConstructor(); constructor.setAccessible(true);
            Object defaults = constructor.newInstance();
            Object leftHours = config.getField("blueMapLeftPlayerHours").get(defaults);
            Object leftKey = config.getMethod("keyByName", String.class)
                .invoke(null, "bluemap-left-player-hours");
            check("departed BlueMap heads have a configurable 24-hour default",
                Integer.valueOf(24).equals(leftHours) && leftKey != null,
                "default=" + leftHours + ", key=" + leftKey);
            check("the BlueMap bridge draws a clock on a departed head",
                integration.contains("almin-left-clock")
                    && integration.contains("m.gone?' gone'")
                    && integration.contains("bottom:-5px"),
                "departed-head clock styling missing");
            check("BlueMap messages are accepted only from Almin's own iframe",
                page.contains("e.source!==f.contentWindow")
                    && page.contains("e.origin!==location.origin"),
                "iframe source or same-origin check missing");

            Files.writeString(cfg.resolve("webapp.conf"),
                "webroot: bluemap/custom-web\n");
            Object unquoted = configure.invoke(null, root, 8123);
            check("an unquoted instance-local BlueMap webroot is honoured",
                (boolean) ok.invoke(unquoted)
                    && Files.isRegularFile(root.resolve(
                        "bluemap/custom-web/js/almin-bridge.js")),
                unquoted.toString());

            Path outside = Files.createTempDirectory("almin-bluemap-outside-");
            Files.writeString(cfg.resolve("webapp.conf"),
                "webroot: \"" + outside.toAbsolutePath() + "\"\n");
            Object refused = configure.invoke(null, root, 8123);
            check("a custom webroot cannot escape the Minecraft instance",
                !(boolean) ok.invoke(refused), refused.toString());

            Path linked = root.resolve("bluemap/linked-web");
            Files.createDirectories(linked.getParent());
            Files.createSymbolicLink(linked, outside);
            Files.writeString(cfg.resolve("webapp.conf"),
                "webroot: \"bluemap/linked-web\"\n");
            Object symlinkRefused = configure.invoke(null, root, 8123);
            check("an instance-local symlink cannot redirect the bridge outside",
                !(boolean) ok.invoke(symlinkRefused), symlinkRefused.toString());
            Files.deleteIfExists(linked);
            delete(outside);
        } finally {
            delete(root);
        }

        System.out.println(failures == 0 ? "\nBLUEMAP TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static boolean bool(Object record, String name) throws Exception {
        Method m = record.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return (boolean) m.invoke(record);
    }

    static String section(String text, String start, int length) {
        int at = text.indexOf(start);
        return at < 0 ? "" : text.substring(at, Math.min(text.length(), at + length));
    }

    static void fakeBlueMap(Path jar) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(("{\"schemaVersion\":1,\"id\":\"bluemap\",\"name\":\"BlueMap\","
                + "\"version\":\"5.23\"}").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
