import com.schecks.almin.AlminConfig;
import com.schecks.almin.WebFiles;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Set;

/**
 * The rules an upload has to satisfy, against a real directory.
 *
 * The policy used to be resolved on the server thread, so it could only be
 * exercised with a running game and every failure came back as "the server
 * didn't answer". It is path arithmetic and the config, so it is checked here
 * for real — and the reason a refusal gives is checked too, because "upload
 * failed" is not a thing anyone can act on.
 */
public class UploadTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static Path root;
    static Set<String> writable = Set.of("mods", "config", "resourcepacks", "shared");

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cc.newInstance());

        root = Files.createTempDirectory("alminupload");
        Files.createDirectories(root.resolve("mods"));
        Files.createDirectories(root.resolve("config/almin"));
        Files.createDirectories(root.resolve("world/datapacks"));
        Files.createDirectories(root.resolve("logs"));

        allowed();
        refused();
        roundTrip();
        download();

        System.out.println(fail == 0 ? "\nUPLOAD TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static WebFiles.Target target(String rel) {
        return WebFiles.uploadTarget(root, writable, root.resolve("world/datapacks"), rel);
    }

    static void allowed() {
        for (String p : new String[]{
                "mods/sodium.jar",
                "mods/nested/thing.jar",
                "config/almin/config.json",
                "resourcepacks/pack.zip",
                "shared/notes.txt",
                "world/datapacks/pack.zip"}) {
            WebFiles.Target t = target(p);
            ck("accepts " + p, t.ok(), t.problem());
        }
    }

    static void refused() {
        // Escaping the server directory, in every shape.
        for (String p : new String[]{"../outside.txt", "mods/../../outside.txt", "/etc/passwd"}) {
            WebFiles.Target t = target(p);
            ck("refuses " + p, !t.ok(), "accepted -> " + t.path());
        }

        // Somewhere writes are not allowed at all.
        WebFiles.Target logs = target("logs/latest.log");
        ck("refuses a folder that is not writable", !logs.ok(), "accepted");
        ck("...and names the folder it refused", logs.problem().contains("logs"), logs.problem());
        ck("...and says which are allowed", logs.problem().contains("mods"), logs.problem());

        WebFiles.Target rootFile = target("");
        ck("refuses the server directory itself", !rootFile.ok(), "accepted");

        WebFiles.Target dir = target("mods");
        ck("refuses a path that is an existing folder", !dir.ok(), "accepted");
        ck("...and says so", dir.problem().toLowerCase().contains("folder"), dir.problem());
    }

    /** The bytes have to actually land, with the temp file cleaned up. */
    static void roundTrip() throws Exception {
        WebFiles.Target t = target("mods/example.jar");
        ck("target resolves under the root", t.ok(), t.problem());

        byte[] payload = new byte[64 * 1024 + 7];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);

        Path tmp = Files.createTempFile(t.path().getParent(), ".almin-upload-", ".part");
        Files.write(tmp, payload);
        Files.move(tmp, t.path(), StandardCopyOption.REPLACE_EXISTING);

        ck("the file exists afterwards", Files.isRegularFile(t.path()), "");
        ck("...with every byte intact",
            java.util.Arrays.equals(payload, Files.readAllBytes(t.path())), "content differs");

        // Replacing an existing file must work too — that is the common case.
        Path tmp2 = Files.createTempFile(t.path().getParent(), ".almin-upload-", ".part");
        Files.write(tmp2, "replaced".getBytes());
        Files.move(tmp2, t.path(), StandardCopyOption.REPLACE_EXISTING);
        ck("an upload replaces an existing file",
            "replaced".equals(Files.readString(t.path())), Files.readString(t.path()));

        long leftovers;
        try (var s = Files.list(t.path().getParent())) {
            leftovers = s.filter(p -> p.getFileName().toString().startsWith(".almin-upload-")).count();
        }
        ck("no .part files are left behind", leftovers == 0, leftovers + " left");
    }

    /** Reads are allowed anywhere under the root; folders and strays are not. */
    static void download() throws Exception {
        Files.writeString(root.resolve("logs/latest.log"), "a log line");
        ck("a file outside the writable roots can still be downloaded",
            WebFiles.downloadable(root, "logs/latest.log") != null, "refused");
        ck("a folder is not downloadable",
            WebFiles.downloadable(root, "mods") == null, "accepted");
        ck("a missing file is not downloadable",
            WebFiles.downloadable(root, "mods/nope.jar") == null, "accepted");
        ck("traversal is refused on the way out too",
            WebFiles.downloadable(root, "../../etc/passwd") == null, "accepted");
    }
}
