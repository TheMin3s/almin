import com.schecks.almin.AlminConfig;
import com.schecks.almin.Backups;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * Copies of the world, and the rules that delete them again.
 *
 * <p>Most of what is checked here is the deleting. Making a backup is a file
 * copy and either works or throws; deciding which of eleven files to destroy
 * is where a mistake is silent, permanent, and only discovered on the day
 * somebody needs the one that is gone. So the retention rules get the bulk of
 * this file, and the rule that outranks all of them — never delete the newest
 * — is checked against each limit separately as well as against all three at
 * once.
 */
public class BackupsTests {
    static int fail = 0;
    static AlminConfig cfg;

    static void ck(String what, boolean ok, String saw) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (ok ? "" : "  -> " + saw));
        if (!ok) fail++;
    }

    static final long HOUR = 3_600_000L;
    static final long DAY = 86_400_000L;

    /** One backup on the disk: a real file, of a chosen size, at a chosen age. */
    static Path make(Path dir, String name, long bytes, long agoMs) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(dir);
        Files.write(f, new byte[(int) bytes]);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - agoMs));
        return f;
    }

    static List<String> names(Path dir) {
        return Backups.listIn(dir).stream().map(Backups.Entry::name).toList();
    }

    public static void main(String[] args) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, cfg);

        defaults();
        naming();
        listing();
        retention();
        newestIsSafe();
        picking();
        words();
        wiring();

        System.out.println(fail == 0 ? "\nBACKUP TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---------- the defaults ----------

    static void defaults() throws Exception {
        // The one that matters: a mod nobody configured must not start writing
        // copies of a world to a disk it knows nothing about.
        ck("automatic backups are off until somebody says otherwise",
            !cfg.backupEnabled, String.valueOf(cfg.backupEnabled));
        ck("...and that is a setting, not a hard-coded no",
            AlminConfig.keyByName("backup-enabled") != null, "no backup-enabled key");
        ck("every retention rule is configurable",
            AlminConfig.keyByName("backup-interval-hours") != null
                && AlminConfig.keyByName("backup-keep") != null
                && AlminConfig.keyByName("backup-keep-days") != null
                && AlminConfig.keyByName("backup-max-gb") != null
                && AlminConfig.keyByName("backup-folder") != null,
            "a retention key is missing");
        // Zero here would mean "no backups", which is not a thing anybody
        // wants to be able to type by accident into a backup system.
        AlminConfig.Key keep = AlminConfig.keyByName("backup-keep");
        Field min = AlminConfig.Key.class.getDeclaredField("min");
        min.setAccessible(true);
        ck("...and none of them can be set to keep nothing",
            ((Integer) min.get(keep)) >= 1, String.valueOf(min.get(keep)));
    }

    // ---------- where they go and what they are called ----------

    static void naming() throws Exception {
        Path server = Files.createTempDirectory("almin-bk-srv");
        cfg.backupFolder = "backups";
        ck("backups land beside the server by default",
            Backups.folderIn(server).equals(server.toAbsolutePath().normalize()
                .resolve("backups")),
            String.valueOf(Backups.folderIn(server)));

        // The arrangement that actually survives the failure backups are for.
        Path other = Files.createTempDirectory("almin-bk-other");
        cfg.backupFolder = other.toString();
        ck("...and an absolute path is honoured, so they can go on another disk",
            Backups.folderIn(server).equals(other.toAbsolutePath().normalize()),
            String.valueOf(Backups.folderIn(server)));

        cfg.backupFolder = "  ";
        ck("...and a blank setting falls back rather than writing to the server root",
            Backups.folderIn(server).getFileName().toString().equals("backups"),
            String.valueOf(Backups.folderIn(server)));
        cfg.backupFolder = "backups";

        String src = Files.readString(Path.of("src/main/java/com/schecks/almin/Backups.java"));
        ck("a backup's name carries the time it was taken",
            src.contains("yyyy-MM-dd_HH-mm-ss"), "no timestamp in the name");
        ck("...and says whether the clock or a person asked for it",
            src.contains("(auto ? \"-auto\" : \"\")"), "manual and automatic look the same");
    }

    // ---------- reading the folder ----------

    static void listing() throws Exception {
        Path dir = Files.createTempDirectory("almin-bk-list");
        cfg.backupFolder = dir.toString();

        ck("an empty folder is no backups rather than an error",
            Backups.listIn(dir).isEmpty(), String.valueOf(Backups.listIn(dir).size()));
        ck("...and so is a folder that is not there",
            Backups.listIn(dir.resolve("nope")).isEmpty(), "invented a backup");

        make(dir, "world-2026-01-01_00-00-00.zip", 10, 3 * HOUR);
        make(dir, "world-2026-01-02_00-00-00-auto.zip", 20, HOUR);
        // Things that are not backups: a half-written one, and whatever else
        // somebody has put in the folder.
        make(dir, "world-2026-01-03_00-00-00.zip.part", 30, 0);
        make(dir, "notes.txt", 40, 0);

        List<Backups.Entry> got = Backups.listIn(dir);
        ck("only backups are listed", got.size() == 2, String.valueOf(names(dir)));
        ck("...a half-written one is not offered as a backup",
            names(dir).stream().noneMatch(n -> n.endsWith(".part")), String.valueOf(names(dir)));
        ck("newest first", got.get(0).name().contains("01-02"), got.get(0).name());
        ck("...with its size", got.get(0).bytes() == 20, String.valueOf(got.get(0).bytes()));
        ck("...and whether it was automatic", got.get(0).auto() && !got.get(1).auto(),
            got.get(0).auto() + "/" + got.get(1).auto());
        ck("the folder's total is the sum of them",
            Backups.listIn(dir).stream().mapToLong(Backups.Entry::bytes).sum() == 30,
            "wrong total");
    }

    // ---------- the three limits ----------

    static void retention() throws Exception {
        // How many.
        Path dir = Files.createTempDirectory("almin-bk-keep");
        cfg.backupFolder = dir.toString();
        cfg.backupKeep = 3;
        cfg.backupKeepDays = 0;
        cfg.backupMaxGb = 0;
        for (int i = 0; i < 6; i++) make(dir, "world-a" + i + ".zip", 10, i * HOUR);
        Backups.sweepIn(dir);
        ck("the count limit keeps the newest few", Backups.listIn(dir).size() == 3,
            String.valueOf(names(dir)));
        ck("...and they are the newest few, not any three",
            names(dir).equals(List.of("world-a0.zip", "world-a1.zip", "world-a2.zip")),
            String.valueOf(names(dir)));

        // How old.
        Path age = Files.createTempDirectory("almin-bk-age");
        cfg.backupFolder = age.toString();
        cfg.backupKeep = 100;
        cfg.backupKeepDays = 7;
        cfg.backupMaxGb = 0;
        make(age, "world-new.zip", 10, HOUR);
        make(age, "world-mid.zip", 10, 3 * DAY);
        make(age, "world-old.zip", 10, 30 * DAY);
        Backups.sweepIn(age);
        ck("the age limit deletes what is too old",
            names(age).equals(List.of("world-new.zip", "world-mid.zip")),
            String.valueOf(names(age)));

        // How much disk. 1 GB ceiling, four 400 MB backups: the running total
        // passes the ceiling partway down the list, and everything below goes.
        Path size = Files.createTempDirectory("almin-bk-size");
        cfg.backupFolder = size.toString();
        cfg.backupKeep = 100;
        cfg.backupKeepDays = 0;
        cfg.backupMaxGb = 1;
        long mb400 = 400L * 1024 * 1024;
        for (int i = 0; i < 4; i++) sparse(size, "world-s" + i + ".zip", mb400, i * HOUR);
        Backups.sweepIn(size);
        ck("the size limit deletes down to the ceiling",
            names(size).equals(List.of("world-s0.zip", "world-s1.zip")),
            String.valueOf(names(size)));

        // Off means off, for each of them.
        Path off = Files.createTempDirectory("almin-bk-off");
        cfg.backupFolder = off.toString();
        cfg.backupKeep = 500;
        cfg.backupKeepDays = 0;
        cfg.backupMaxGb = 0;
        for (int i = 0; i < 5; i++) make(off, "world-o" + i + ".zip", 10, i * 400 * DAY);
        Backups.sweepIn(off);
        ck("a zero age limit means no age limit", Backups.listIn(off).size() == 5,
            String.valueOf(names(off)));
    }

    /** A file that claims a size without costing one, for the GB-scale checks. */
    static void sparse(Path dir, String name, long bytes, long agoMs) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(dir);
        try (var ch = java.nio.channels.FileChannel.open(f,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE)) {
            ch.position(bytes - 1);
            ch.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - agoMs));
    }

    // ---------- the rule that outranks the rules ----------

    static void newestIsSafe() throws Exception {
        // Each limit on its own, set to a value that would delete everything
        // if the newest were not exempt. This is the check that matters most:
        // a retention rule that can empty the folder is worse than none.
        Path a = Files.createTempDirectory("almin-bk-safe1");
        cfg.backupFolder = a.toString();
        cfg.backupKeep = 1;
        cfg.backupKeepDays = 0;
        cfg.backupMaxGb = 0;
        make(a, "world-x1.zip", 10, HOUR);
        make(a, "world-x2.zip", 10, 2 * HOUR);
        Backups.sweepIn(a);
        ck("keeping one keeps one, rather than none",
            names(a).equals(List.of("world-x1.zip")), String.valueOf(names(a)));

        Path b = Files.createTempDirectory("almin-bk-safe2");
        cfg.backupFolder = b.toString();
        cfg.backupKeep = 100;
        cfg.backupKeepDays = 1;
        cfg.backupMaxGb = 0;
        make(b, "world-y1.zip", 10, 100 * DAY);
        make(b, "world-y2.zip", 10, 200 * DAY);
        Backups.sweepIn(b);
        ck("...and a folder where everything is too old still keeps the newest",
            names(b).equals(List.of("world-y1.zip")), String.valueOf(names(b)));

        Path c = Files.createTempDirectory("almin-bk-safe3");
        cfg.backupFolder = c.toString();
        cfg.backupKeep = 100;
        cfg.backupKeepDays = 0;
        cfg.backupMaxGb = 1;
        sparse(c, "world-z1.zip", 4L * 1024 * 1024 * 1024, HOUR);
        sparse(c, "world-z2.zip", 4L * 1024 * 1024 * 1024, 2 * HOUR);
        Backups.sweepIn(c);
        ck("...and one backup bigger than the whole ceiling is still kept",
            names(c).equals(List.of("world-z1.zip")), String.valueOf(names(c)));

        // All three at once, all impossible.
        Path d = Files.createTempDirectory("almin-bk-safe4");
        cfg.backupFolder = d.toString();
        cfg.backupKeep = 1;
        cfg.backupKeepDays = 1;
        cfg.backupMaxGb = 1;
        sparse(d, "world-w1.zip", 9L * 1024 * 1024 * 1024, 90 * DAY);
        sparse(d, "world-w2.zip", 9L * 1024 * 1024 * 1024, 91 * DAY);
        Backups.sweepIn(d);
        ck("...and every rule failing at once still leaves a backup",
            names(d).equals(List.of("world-w1.zip")), String.valueOf(names(d)));

        // The only thing that deletes the last one is a person saying so.
        Backups.Result r = Backups.deleteIn(d, "world-w1.zip");
        ck("a person can still delete the newest by hand",
            r.ok() && Backups.listIn(d).isEmpty(), r.message());

        Path lone = Files.createTempDirectory("almin-bk-lone");
        cfg.backupFolder = lone.toString();
        cfg.backupKeep = 1;
        cfg.backupKeepDays = 1;
        make(lone, "world-only.zip", 10, 400 * DAY);
        Backups.sweepIn(lone);
        ck("a folder with one backup in it is never swept to nothing",
            Backups.listIn(lone).size() == 1, String.valueOf(names(lone)));
    }

    // ---------- naming one from a request ----------

    static void picking() throws Exception {
        Path dir = Files.createTempDirectory("almin-bk-pick");
        cfg.backupFolder = dir.toString();
        make(dir, "world-p1.zip", 10, HOUR);
        Path outside = dir.getParent().resolve("secrets.txt");
        Files.writeString(outside, "not yours", StandardCharsets.UTF_8);

        ck("a backup can be named for download",
            Backups.fileIn(dir, "world-p1.zip") != null, "could not find it");
        // Names are matched against the listing rather than resolved as paths,
        // so there is nothing to escape from.
        ck("...but nothing outside the folder can be",
            Backups.fileIn(dir, "../secrets.txt") == null, "climbed out of the folder");
        ck("...however it is spelled",
            Backups.fileIn(dir, "..%2Fsecrets.txt") == null
                && Backups.fileIn(dir, "/etc/passwd") == null
                && Backups.fileIn(dir, "world-p1.zip/../../secrets.txt") == null,
            "a path got through");
        ck("...and a file in the folder that is not a backup cannot be either",
            Backups.fileIn(dir, "notes.txt") == null, "handed out a non-backup");
        ck("deleting something that is not there says so rather than throwing",
            !Backups.deleteIn(dir, "world-nope.zip").ok(), "claimed to delete nothing");
        ck("...and an empty name is not a file either",
            Backups.fileIn(dir, "") == null && Backups.fileIn(dir, null) == null,
            "an empty name found something");
        ck("the file outside the folder is still there afterwards",
            Files.exists(outside), "a test deleted something it should not have");
    }

    // ---------- what it says ----------

    static void words() {
        ck("sizes are said in units people read",
            Backups.human(512).equals("512 B")
                && Backups.human(2048).equals("2.00 KB")
                && Backups.human(5L * 1024 * 1024 * 1024).startsWith("5.00 GB"),
            Backups.human(2048) + " / " + Backups.human(5L * 1024 * 1024 * 1024));
    }

    // ---------- how it is wired in ----------

    static void wiring() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/schecks/almin/Backups.java"));

        // The three steps that make a copy restorable rather than a copy of a
        // world caught mid-write.
        ck("saving is paused before the copy",
            src.contains("mc.setAutoSave(false)") && src.contains("level.noSave = true"),
            "the world is copied while it is being written");
        ck("...everything is flushed to disk first",
            src.contains("mc.saveEverything(true, true, true)"), "nothing is flushed");
        ck("...and saving is turned back on afterwards",
            src.contains("mc.setAutoSave(true)") && src.contains("level.noSave = false"),
            "saving stays off");
        int held = src.indexOf("boolean held = hold(mc, true);");
        int finallyRestore = src.indexOf("if (held) hold(mc, false);");
        ck("...even when the copy fails, because it is in a finally",
            held > 0 && finallyRestore > held
                && src.substring(held, finallyRestore).contains("} finally {"),
            "restoring saving is not in a finally");

        // The copy itself must not run on the tick loop.
        ck("the copy happens off the server thread",
            src.contains("new Thread(() -> run(mc"), "the copy blocks the game");
        ck("...and only one at a time",
            src.contains("running.compareAndSet(false, true)"), "two can run at once");

        ck("a half-written backup is deleted rather than left looking like one",
            src.contains("Files.deleteIfExists(target)"), "a failed run leaves a broken zip");
        ck("...and a finished one is moved into place rather than written in place",
            src.contains(".part") && src.contains("Files.move(tmp, target"),
            "a backup exists under its real name before it is complete");

        ck("the server's own lock file is not copied into the backup",
            src.contains("session.lock"), "session.lock is in the zip");
        ck("the backup folder is not copied into the backup",
            src.contains("SKIP_SUBTREE"), "backups of backups");
        ck("the lists that decide who may join travel with the world",
            src.contains("ops.json") && src.contains("whitelist.json")
                && src.contains("server.properties"),
            "the world comes back without its op list");

        ck("it refuses to start when the disk has no room",
            src.contains("getUsableSpace()"), "it will happily fill the disk");

        // Deliberately absent. A restore button is one misclick from deleting
        // the thing the backups exist to protect.
        ck("there is no restore, and nothing pretends there is",
            !src.contains("restore(") && !src.contains("unzip"), "something restores");

        String cfgSrc = Files.readString(Path.of("src/main/java/com/schecks/almin/AlminConfig.java"));
        ck("the clock is off by default in the file people read",
            cfgSrc.contains("public boolean backupEnabled = false;"), "on by default");

        String almin = Files.readString(Path.of("src/main/java/com/schecks/almin/Almin.java"));
        ck("the clock starts once there is a world to copy",
            almin.contains("SERVER_STARTED.register(Backups::init)"), "started too early");
        ck("...and stops with the server",
            almin.contains("Backups.close()"), "the timer outlives the server");

        String web = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
        for (String route : new String[] {"/api/backups", "/api/backups/start",
                                          "/api/backups/delete", "/api/backups/file"}) {
            ck("the " + route + " route is behind the backups menu",
                web.contains("java.util.Map.entry(\"" + route + "\", \"backups\")"),
                "unguarded or guarded as something else");
        }
        ck("making one is a write, not a read",
            web.contains("private void handleBackupStart") && startGuardedByWrite(web),
            "a read-only account can start a backup");
        ck("...and so is deleting one",
            deleteGuardedByWrite(web), "a read-only account can delete a backup");

        String acc = Files.readString(Path.of("src/main/java/com/schecks/almin/Accounts.java"));
        ck("backups is a menu an account can be given or refused",
            acc.contains("\"backups\""), "not in the menu list");

        String page = Files.readString(Path.of("src/main/java/com/schecks/almin/WebPage.java"));
        ck("the panel has a Backups tab",
            page.contains("['backups','Backups']"), "no tab");
        ck("...which is named the same in both places",
            page.contains("backups:'Backups'"), "the tab and the label disagree");
    }

    static boolean startGuardedByWrite(String web) {
        int at = web.indexOf("private void handleBackupStart");
        int end = web.indexOf("\n    }", at);
        return at > 0 && web.substring(at, end).contains("requireWrite(ex, \"backups\")");
    }

    static boolean deleteGuardedByWrite(String web) {
        int at = web.indexOf("private void handleBackupDelete");
        int end = web.indexOf("\n    }", at);
        return at > 0 && web.substring(at, end).contains("requireWrite(ex, \"backups\")");
    }
}
