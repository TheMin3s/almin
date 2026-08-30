import com.schecks.almin.ServerMods;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The folder Fabric reads.
 *
 * Everything here is about a directory on disk, so it is exercised on a real
 * one: a temporary mods/ with real jars in it, including one pretending to be
 * Almin's own. What matters is that nothing can reach outside that folder and
 * that the panel cannot switch off the jar it is running from.
 */
public class ServerModTests {
    static int failures = 0;
    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    /** A jar with a real fabric.mod.json in it, which is all ModJars reads. */
    static void jar(Path at, String id, String name, String version) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.write(at, out.toByteArray());
    }

    public static void main(String[] a) throws Exception {
        Path root = Files.createTempDirectory("almin-servermods");
        Path mods = Files.createDirectories(root.resolve("mods"));
        jar(mods.resolve("carpet-1.4.jar"), "carpet", "Carpet", "1.4");
        jar(mods.resolve("ledger-1.3.jar.disabled"), "ledger", "Ledger", "1.3");
        jar(mods.resolve("almin-2.25.0.jar"), "almin", "Almin", "2.25.0");
        Files.writeString(mods.resolve("notes.txt"), "not a mod");

        // ---- what it can reach ----
        check("a plain jar name is fine", safe(mods, "sodium-0.6.jar"));
        check("a disabled jar name is fine too", safe(mods, "sodium-0.6.jar.disabled"));
        check("a path cannot be smuggled in", !safe(mods, "../server.properties"));
        check("  nor a bare slash", !safe(mods, "sub/x.jar"));
        check("  nor a backslash", !safe(mods, "sub\\x.jar"));
        check("something that is not a jar is refused", !safe(mods, "server.properties"));
        check("an empty name is refused", !safe(mods, "  "));

        // ---- listing ----
        List<ServerMods.Installed> list = listOf(mods);
        check("only jars are listed: " + list.size(), list.size() == 3);
        ServerMods.Installed carpet = find(list, "carpet-1.4.jar");
        ServerMods.Installed ledger = find(list, "ledger-1.3.jar.disabled");
        ServerMods.Installed almin = find(list, "almin-2.25.0.jar");
        check("a jar's own name is read out of it",
            carpet != null && carpet.name().equals("Carpet") && carpet.version().equals("1.4"));
        check("a .disabled jar is listed as off", ledger != null && !ledger.enabled());
        check("  and an ordinary one as on", carpet != null && carpet.enabled());
        check("Almin knows its own jar", almin != null && almin.ours());
        check("nothing is claimed to be loaded that this process has not loaded",
            carpet != null && !carpet.loaded());
        check("enabled jars come first",
            list.get(list.size() - 1).file().endsWith(".disabled"));

        // ---- turning one off and on ----
        ServerMods.Result off = setEnabled(mods, "carpet-1.4.jar", false);
        check("a jar can be turned off: " + off.message(), off.ok());
        check("  by renaming rather than deleting",
            Files.exists(mods.resolve("carpet-1.4.jar.disabled"))
            && !Files.exists(mods.resolve("carpet-1.4.jar")));
        check("  and it says the change is not live yet",
            off.message().contains("next start"));
        ServerMods.Result on = setEnabled(mods, "carpet-1.4.jar.disabled", true);
        check("and back on again", on.ok() && Files.exists(mods.resolve("carpet-1.4.jar")));

        ServerMods.Result again = setEnabled(mods, "carpet-1.4.jar", true);
        check("turning on something already on is not an error", again.ok());

        // ---- the one jar it must not touch ----
        ServerMods.Result ours = setEnabled(mods, "almin-2.25.0.jar", false);
        check("Almin will not switch itself off: " + ours.message(), !ours.ok());
        check("  and says why", ours.message().contains("panel"));
        ServerMods.Result gone = delete(mods, "almin-2.25.0.jar");
        check("nor delete itself", !gone.ok());
        check("  and points at the updater", gone.message().contains("updater"));

        // ---- deleting ----
        ServerMods.Result rm = delete(mods, "ledger-1.3.jar.disabled");
        check("a jar can be deleted: " + rm.message(), rm.ok());
        check("  and it is really gone",
            !Files.exists(mods.resolve("ledger-1.3.jar.disabled")));
        check("deleting something that is not there is refused",
            !delete(mods, "nothing.jar").ok());

        // ---- installing ----
        Path staged = Files.createTempFile(root, "dl-", ".part");
        jar(staged, "sodium", "Sodium", "0.6.13");
        ServerMods.Result put = install(mods, staged, "sodium-0.6.13.jar", false);
        check("a finished download is moved in: " + put.message(), put.ok());
        check("  and named after the mod inside it", put.message().contains("Sodium"));
        check("  and says it needs a restart", put.message().contains("next server start"));
        check("  and nothing half-written is left behind",
            !Files.exists(staged) && Files.exists(mods.resolve("sodium-0.6.13.jar")));

        Path second = Files.createTempFile(root, "dl-", ".part");
        jar(second, "sodium", "Sodium", "0.6.14");
        check("a second copy under the same name is refused by default",
            !install(mods, second, "sodium-0.6.13.jar", false).ok());
        check("  unless replacing was asked for",
            install(mods, second, "sodium-0.6.13.jar", true).ok());

        System.out.println(failures == 0 ? "SERVER MODS OK" : "SERVER MOD FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    static ServerMods.Installed find(List<ServerMods.Installed> list, String file) {
        for (ServerMods.Installed m : list) if (m.file().equals(file)) return m;
        return null;
    }

    // ---- the folder, without a MinecraftServer ----
    //
    // Each public method takes a server so it can find the directory, and
    // delegates to one that takes the directory instead. These are the
    // second kind, reached by reflection because they are package-private —
    // nothing outside the mod should be calling them.

    static boolean safe(Path mods, String name) throws Exception {
        return folder("resolve", new Class<?>[] {Path.class, String.class}, mods, name) != null;
    }

    @SuppressWarnings("unchecked")
    static List<ServerMods.Installed> listOf(Path mods) throws Exception {
        return (List<ServerMods.Installed>)
            folder("list", new Class<?>[] {Path.class}, mods);
    }

    static ServerMods.Result setEnabled(Path mods, String file, boolean on) throws Exception {
        return (ServerMods.Result) folder("setEnabled",
            new Class<?>[] {Path.class, String.class, boolean.class}, mods, file, on);
    }

    static ServerMods.Result delete(Path mods, String file) throws Exception {
        return (ServerMods.Result) folder("delete",
            new Class<?>[] {Path.class, String.class}, mods, file);
    }

    static ServerMods.Result install(Path mods, Path staged, String name, boolean replace)
            throws Exception {
        return (ServerMods.Result) folder("install",
            new Class<?>[] {Path.class, Path.class, String.class, boolean.class},
            mods, staged, name, replace);
    }

    static Object folder(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ServerMods.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
}
