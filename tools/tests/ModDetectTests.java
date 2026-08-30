import com.schecks.almin.ModJars;
import com.schecks.almin.Modrinth;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Why a client could not tell it already had a mod, and what now stops that.
 *
 * The advertised id has to be the one in the jar's fabric.mod.json. It was
 * being typed by hand, where it is usually the Modrinth slug or the display
 * name instead, and the check was an exact match on it — so detection failed
 * silently and the player was re-offered a mod they had, every join.
 */
public class ModDetectTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static Method normalise, matches;

    public static void main(String[] a) throws Exception {
        Class<?> installer = Class.forName("com.schecks.almin.client.ClientModInstaller");
        normalise = installer.getDeclaredMethod("normalise", String.class);
        normalise.setAccessible(true);
        matches = installer.getDeclaredMethod("matches", String.class, String.class);
        matches.setAccessible(true);

        naming();
        jarMeta();
        links();

        System.out.println(fail == 0 ? "\nMOD-DETECT TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String norm(String s) throws Exception {
        return (String) normalise.invoke(null, s);
    }
    static boolean match(String want, String candidate) throws Exception {
        return (Boolean) matches.invoke(null, norm(want), candidate);
    }

    /** One mod written several ways is still one mod. */
    static void naming() throws Exception {
        ck("case is not meaningful", norm("Sodium").equals("sodium"), norm("Sodium"));
        ck("spaces are not meaningful", norm("Mod Menu").equals("modmenu"), norm("Mod Menu"));
        ck("hyphens are not meaningful", norm("mod-menu").equals("modmenu"), norm("mod-menu"));
        ck("underscores are not meaningful", norm("fabric_api").equals("fabricapi"), norm("fabric_api"));
        ck("null is empty", norm(null).isEmpty(), "");

        ck("'Mod Menu' finds modmenu", match("Mod Menu", "modmenu"), "");
        ck("'modmenu' finds Mod Menu", match("modmenu", "Mod Menu"), "");
        ck("'Sodium' finds sodium", match("Sodium", "sodium"), "");
        ck("a different mod is not a match", !match("sodium", "lithium"), "");

        // Empty must never match everything — that would mark every mod installed.
        ck("an empty want matches nothing", !match("", "sodium"), "");
        ck("an empty candidate matches nothing", !match("sodium", ""), "");
        ck("empty against empty is still nothing", !match("", ""), "");

        // Punctuation-only strings normalise away; they must not collide.
        ck("punctuation alone is not a match", !match("---", "sodium"), "");
    }

    /** The id must come from the jar, because that is the only place it is right. */
    static void jarMeta() throws Exception {
        Path dir = Files.createTempDirectory("alminjars");

        Path good = dir.resolve("modmenu-fancy-name.jar");
        writeJar(good, "{\"schemaVersion\":1,\"id\":\"modmenu\",\"name\":\"Mod Menu\","
            + "\"version\":\"11.0.3\"}");
        ModJars.Meta meta = ModJars.read(good);
        ck("a jar's id is read out of it", meta.ok() && meta.modId().equals("modmenu"),
            meta.toString());
        ck("...along with its name", meta.name().equals("Mod Menu"), meta.name());
        ck("...and its version", meta.version().equals("11.0.3"), meta.version());
        ck("the filename is not trusted for any of it",
            !meta.modId().equals("modmenu-fancy-name"), meta.modId());

        Path noManifest = dir.resolve("plain.jar");
        writeZip(noManifest, "README.txt", "not a mod");
        ck("a jar with no manifest reads as nothing", !ModJars.read(noManifest).ok(), "");

        Path broken = dir.resolve("broken.jar");
        writeJar(broken, "{ this is not json");
        ck("a broken manifest reads as nothing", !ModJars.read(broken).ok(), "");

        Path noId = dir.resolve("noid.jar");
        writeJar(noId, "{\"schemaVersion\":1,\"name\":\"Nameless\"}");
        ck("a manifest with no id reads as nothing", !ModJars.read(noId).ok(), "");

        Path missing = dir.resolve("absent.jar");
        ck("a missing file reads as nothing", !ModJars.read(missing).ok(), "");
        ck("null reads as nothing", !ModJars.read(null).ok(), "");

        // A name that is absent falls back to the id rather than being blank.
        Path unnamed = dir.resolve("unnamed.jar");
        writeJar(unnamed, "{\"schemaVersion\":1,\"id\":\"quiet\",\"version\":\"1.0\"}");
        ck("a nameless mod falls back to its id",
            ModJars.read(unnamed).name().equals("quiet"), ModJars.read(unnamed).name());
    }

    /** What a person actually pastes has to work. */
    static void links() {
        String[][] good = {
            {"https://modrinth.com/mod/modmenu", "modmenu"},
            {"http://modrinth.com/mod/sodium", "sodium"},
            {"modrinth.com/mod/lithium", "lithium"},
            {"https://modrinth.com/mod/modmenu/versions", "modmenu"},
            {"https://modrinth.com/plugin/something", "something"},
            {"https://modrinth.com/mod/modmenu?version=1", "modmenu"},
            {"  https://modrinth.com/mod/modmenu  ", "modmenu"},
            {"sodium", "sodium"},
        };
        for (String[] c : good) {
            String got = Modrinth.slugFrom(c[0]);
            ck("'" + c[0].trim() + "' -> " + c[1], c[1].equals(got), got);
        }
        for (String bad : new String[]{"", null, "https://example.com/mod/evil",
                                       "https://modrinth.com", "not a slug at all"}) {
            ck("refuses " + bad, Modrinth.slugFrom(bad).isEmpty(), Modrinth.slugFrom(bad));
        }

        // Resolving without a network must fail with a reason, not an exception.
        Modrinth.Resolved r = Modrinth.resolve("not a link", "26.2");
        ck("an unparseable link is refused cleanly", !r.ok() && !r.problem().isEmpty(), r.problem());
    }

    static void writeJar(Path jar, String manifest) throws Exception {
        writeZip(jar, "fabric.mod.json", manifest);
    }

    static void writeZip(Path jar, String entry, String content) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
