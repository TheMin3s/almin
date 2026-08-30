import com.schecks.almin.ModOffers;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;

/** The server-hosted mod file path: filename confinement and offer validation. */
public class ModFileTests {
    static int fail=0;
    static void ck(String w, boolean ok, String d){ System.out.println((ok?"  PASS  ":"  FAIL  ")+w+(ok?"":"  -> "+d)); if(!ok) fail++; }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("alminmf");
        Path modfiles = dir.resolve("modfiles");
        Files.createDirectories(modfiles);
        Files.writeString(modfiles.resolve("sodium.jar"), "x");
        // A file we must never be able to reach from modfiles/
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "top secret");

        Field pf = ModOffers.class.getDeclaredField("path"); pf.setAccessible(true);
        pf.set(null, dir.resolve("mods.json"));
        Field mf = ModOffers.class.getDeclaredField("modFilesDir"); mf.setAccessible(true);
        mf.set(null, modfiles);

        // ---- filename confinement (this is what a client can influence) ----
        ck("plain jar resolves", ModOffers.resolveModFile("sodium.jar") != null, "");
        ck("../ escape blocked", ModOffers.resolveModFile("../secret.txt") == null, "escaped!");
        ck("nested ../ blocked", ModOffers.resolveModFile("a/../../secret.txt") == null, "escaped!");
        ck("subdirectory blocked", ModOffers.resolveModFile("sub/x.jar") == null, "");
        ck("backslash blocked", ModOffers.resolveModFile("..\\secret.txt") == null, "");
        ck("absolute path blocked", ModOffers.resolveModFile("/etc/passwd") == null, "");
        ck("non-jar blocked", ModOffers.resolveModFile("secret.txt") == null, "non-jar allowed!");
        ck("server.properties blocked", ModOffers.resolveModFile("server.properties") == null, "");
        ck("empty blocked", ModOffers.resolveModFile("") == null, "");
        ck("null blocked", ModOffers.resolveModFile(null) == null, "");

        // ---- offers ----
        ck("available lists the jar", ModOffers.availableFiles().equals(List.of("sodium.jar")),
            String.valueOf(ModOffers.availableFiles()));

        var good = new ModOffers.AdvertisedMod("sodium","Sodium","1","", "", false, "sodium.jar");
        ck("server-hosted offer accepted", ModOffers.add(good) == ModOffers.AddResult.OK, "");
        ck("offer reports serverHosted", ModOffers.list().get(0).serverHosted(), "");

        var missing = new ModOffers.AdvertisedMod("ghost","Ghost","1","", "", false, "nope.jar");
        ck("offer for absent file -> MISSING_FILE",
            ModOffers.add(missing) == ModOffers.AddResult.MISSING_FILE, "");

        var traversal = new ModOffers.AdvertisedMod("evil","Evil","1","", "", false, "../secret.txt");
        ck("offer with traversal filename -> BAD_FILE",
            ModOffers.add(traversal) == ModOffers.AddResult.BAD_FILE, "");

        var noSource = new ModOffers.AdvertisedMod("none","None","1","", "", false, "");
        ck("offer with neither source -> BAD_URL",
            ModOffers.add(noSource) == ModOffers.AddResult.BAD_URL, "");

        // toggling required keeps the file source
        ModOffers.setRequired("sodium", true);
        ck("required toggle preserves file source",
            ModOffers.list().get(0).file().equals("sodium.jar") && ModOffers.list().get(0).required(), "");

        // reload drops offers whose file vanished
        Files.delete(modfiles.resolve("sodium.jar"));
        ModOffers.reload();
        ck("reload drops offer whose file is gone", ModOffers.count() == 0, "count=" + ModOffers.count());

        System.out.println(fail==0?"\nMOD-FILE TESTS PASSED":"\n"+fail+" FAILED");
        System.exit(fail==0?0:1);
    }
}
