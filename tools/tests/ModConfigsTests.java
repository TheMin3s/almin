import com.schecks.almin.ModConfigs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Which file in {@code config/} belongs to which mod.
 *
 * <p>There is no standard for this, so the answer is a guess made out of
 * filenames. Two things follow. A guess that misses is a nuisance — the file
 * browser is still there — so the misses are checked loosely. A guess that
 * hits the wrong file is offered to somebody as that mod's settings and then
 * written over, so the wrong hits are checked hard: another mod's file, a
 * neighbouring name, a path climbing out of the folder, and Almin's own
 * credentials.
 */
public class ModConfigsTests {
    static int fail = 0;

    static void ck(String what, boolean ok, String saw) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (ok ? "" : "  -> " + saw));
        if (!ok) fail++;
    }

    static Path root;

    static void file(String rel, String body) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    static List<String> paths(String modId, String jar) {
        return ModConfigs.of(root, modId, jar).stream().map(ModConfigs.Entry::path).toList();
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("almin-modcfg");

        // A config folder off a real server, near enough.
        file("sodium.json", "{}");
        file("sodiumextra.json", "{}");          // a different mod, similar name
        file("sodium-mixins.properties", "a=b"); // the same mod, suffixed
        file("carpet/carpet.conf", "commandSpawn true");
        file("carpet/rules.json", "{}");
        file("carpet/cache/blobs.bin", "\0\0");  // not text, not settings
        file("carpet-extra.conf", "x=1");
        file("ftbchunks-client.toml", "a=1");
        file("almin/config.json", "{\"password\":\"secret\"}");
        file("almin/accounts.json", "[]");
        file("deep/a/b/c/d/e/buried.json", "{}");

        // ---- the easy shapes ----
        ck("a file named after the mod is that mod's settings",
            paths("sodium", "sodium-fabric-0.6.13.jar").contains("sodium.json"),
            String.valueOf(paths("sodium", "sodium-fabric-0.6.13.jar")));
        ck("...and so is one with something on the end of that name",
            paths("sodium", "sodium-fabric-0.6.13.jar").contains("sodium-mixins.properties"),
            String.valueOf(paths("sodium", "sodium-fabric-0.6.13.jar")));
        // The one that matters: "sodium" is a prefix of "sodiumextra", and
        // offering somebody another mod's settings to edit is worse than
        // offering them nothing.
        ck("a mod whose name merely starts the same is not claimed",
            !paths("sodium", "sodium-fabric-0.6.13.jar").contains("sodiumextra.json"),
            "sodium was given sodiumextra.json");
        ck("a folder named after the mod is all of it",
            paths("carpet", "fabric-carpet-1.4.163.jar")
                .containsAll(List.of("carpet/carpet.conf", "carpet/rules.json")),
            String.valueOf(paths("carpet", "fabric-carpet-1.4.163.jar")));
        ck("...but the data it keeps in there is not settings",
            paths("carpet", "fabric-carpet-1.4.163.jar").stream()
                .noneMatch(p -> p.endsWith(".bin")),
            "a binary file was offered to a text editor");
        ck("a mod filed under its jar rather than its id is still found",
            paths("carpetextra", "carpet-extra-1.4.163.jar").contains("carpet-extra.conf"),
            String.valueOf(paths("carpetextra", "carpet-extra-1.4.163.jar")));
        ck("a file storage rather than settings is left alone",
            paths("deep", "deep-1.0.jar").isEmpty(),
            String.valueOf(paths("deep", "deep-1.0.jar")));

        // ---- Almin's own ----
        // The Mods menu is a different door from the Settings menu. An account
        // allowed through one is not thereby allowed through the other, and
        // config/almin holds the password that decides who is allowed at all.
        ck("Almin's own settings are not offered as a mod's settings",
            paths("almin", "almin-2.67.0.jar").isEmpty(),
            String.valueOf(paths("almin", "almin-2.67.0.jar")));
        ck("...and cannot be reached by asking for one of them",
            ModConfigs.file(root, "almin", "almin-2.67.0.jar", "almin/config.json") == null,
            "the panel's own password was readable through the mods menu");
        ck("...whatever mod claims to own it",
            ModConfigs.file(root, "carpet", "fabric-carpet-1.4.163.jar",
                "almin/accounts.json") == null,
            "another mod reached Almin's accounts");

        // ---- what a path is allowed to be ----
        {
            String jar = "fabric-carpet-1.4.163.jar";
            ck("a path that was offered opens",
                ModConfigs.file(root, "carpet", jar, "carpet/carpet.conf") != null, "");
            ck("a path that was not offered does not, however it is spelled",
                ModConfigs.file(root, "carpet", jar, "sodium.json") == null
                    && ModConfigs.file(root, "carpet", jar, "carpet/../sodium.json") == null
                    && ModConfigs.file(root, "carpet", jar, "../server.properties") == null
                    && ModConfigs.file(root, "carpet", jar,
                        "/etc/passwd") == null,
                "a path outside the mod's own files resolved");
            ck("nothing is not a path",
                ModConfigs.file(root, "carpet", jar, "") == null
                    && ModConfigs.file(root, "carpet", jar, null) == null,
                "an empty path resolved to something");
        }

        // ---- what the editor will take ----
        {
            file("bigmod.json", "x".repeat(1024));
            Path big = root.resolve("hugemod.json");
            Files.writeString(big, "y".repeat(64));
            ck("a file small enough to edit says so",
                ModConfigs.of(root, "bigmod", "bigmod-1.0.jar").get(0).editable(),
                "a 1KB file was called too big");
            ck("the editor's limit is stated once, where the route can use it",
                ModConfigs.MAX_BYTES == 2L * 1024 * 1024,
                String.valueOf(ModConfigs.MAX_BYTES));
        }

        // ---- names ----
        ck("a version is not part of a mod's name",
            ModConfigs.jarStem("sodium-fabric-0.6.13.jar").equals("sodium-fabric")
                && ModConfigs.jarStem("Xaeros_Minimap_24.2.0_Fabric.jar")
                    .equals("Xaeros_Minimap"),
            ModConfigs.jarStem("sodium-fabric-0.6.13.jar") + " / "
                + ModConfigs.jarStem("Xaeros_Minimap_24.2.0_Fabric.jar"));
        ck("a jar somebody turned off is still the same mod",
            ModConfigs.jarStem("carpet-1.4.163.jar.disabled").equals("carpet"),
            ModConfigs.jarStem("carpet-1.4.163.jar.disabled"));
        ck("hyphens and underscores are the same name",
            ModConfigs.stems("some-mod", "some-mod-1.0.jar").contains("some_mod"),
            String.valueOf(ModConfigs.stems("some-mod", "some-mod-1.0.jar")));
        ck("a mod id that is really a path is not a name",
            ModConfigs.stems("../../etc", "x.jar").stream()
                .noneMatch(s -> s.contains("..")),
            String.valueOf(ModConfigs.stems("../../etc", "x.jar")));

        // ---- the routes ----
        {
            String web = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
            ck("both mod routes are behind the Mods menu",
                web.contains("java.util.Map.entry(\"/api/servermods/mod\", \"mods\")")
                    && web.contains("java.util.Map.entry(\"/api/servermods/config\", \"mods\")"),
                "a mod route is not menu-guarded");
            ck("writing a config is a write, and asks for a secure session",
                web.contains("write ? !requireAuthSecure(ex) : !requireAuth(ex)"),
                "the config route treats a read and a write the same");
            // The point of the route: it never resolves the path the request
            // sent. It looks for it in the list this mod's own files produced.
            ck("the path is looked up rather than resolved",
                web.contains("ModConfigs.file(server, modId, file, rel)")
                    && !web.contains("config\").resolve(rel)"),
                "the request's path is turned into a path of its own");
            ck("a file too big for the editor is refused rather than truncated",
                web.contains("ModConfigs.MAX_BYTES") && web.contains("413"),
                "a huge file would be half-read and written back short");
        }

        System.out.println(fail == 0 ? "\nMOD CONFIG TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
