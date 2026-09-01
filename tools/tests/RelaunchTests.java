import com.schecks.almin.AlminConfig;
import com.schecks.almin.ServerRelaunch;
import com.schecks.almin.WebLock;

import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

/**
 * The two halves of "a restart actually restarts".
 *
 * ServerRelaunch is the half that starts the server again without anyone
 * having configured how. WebLock is the half that takes the port back from a
 * previous instance that is holding it and serving nothing — which is the
 * shape of the bug this whole thing came out of.
 *
 * WebLock ends processes, so most of what is checked here is what it REFUSES
 * to end. Killing the wrong thing because a port was busy would be far worse
 * than the problem being solved.
 */
public class RelaunchTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static AlminConfig cfg;
    static Path dir;
    static String javaBin;

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, cfg);

        ck("self-relaunch is opt-in by default", !cfg.webRestartRelaunch,
            String.valueOf(cfg.webRestartRelaunch));
        cfg.configVersion = 2;
        cfg.webRestartRelaunch = true; // the old persisted default
        Method migrate = AlminConfig.class.getDeclaredMethod("migrate", AlminConfig.class);
        migrate.setAccessible(true);
        migrate.invoke(null, cfg);
        ck("the unsafe old default is migrated off",
            !cfg.webRestartRelaunch && cfg.configVersion == 3,
            cfg.webRestartRelaunch + " / v" + cfg.configVersion);

        dir = Files.createTempDirectory("alminrelaunch");
        Files.createDirectories(dir.resolve("config").resolve("almin"));
        javaBin = ProcessHandle.current().info().command().orElse("java");

        planning();
        arming();
        launching();
        lockRoundTrip();
        refusals();
        theKill();
        wiring();

        System.out.println(fail == 0 ? "\nRELAUNCH TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---------- how the server gets started again ----------

    /** The point of the feature: nobody has to have configured anything. */
    static void planning() {
        cfg.webStartCommand = "";
        ServerRelaunch.Plan derived = ServerRelaunch.plan();
        ck("a plan exists with nothing configured", derived.ok(), derived.problem());
        ck("...built from this process's own command line",
            derived.source().contains("own command line"), derived.source());
        ck("...starting with the java binary that is running now",
            !derived.argv().isEmpty() && derived.argv().get(0).equals(javaBin),
            String.valueOf(derived.argv()));
        ck("...and carrying the arguments too, not just the binary",
            derived.argv().size() > 1, String.valueOf(derived.argv().size()));
        ck("...as a real argv, so nothing has to be quoted",
            derived.argv().size() == derived.display().split(" ").length
                || derived.display().contains(javaBin), derived.display());

        // An operator who typed a command meant it; it wins over the guess.
        cfg.webStartCommand = "./start.sh";
        ServerRelaunch.Plan configured = ServerRelaunch.plan();
        ck("a configured command wins", configured.ok()
            && configured.argv().equals(List.of("/bin/sh", "-c", "./start.sh")),
            String.valueOf(configured.argv()));
        ck("...and says so", configured.source().equals("web-start-command"), configured.source());
        cfg.webStartCommand = "";

        // Relative paths in the argv only resolve where they were typed.
        Path wd = ServerRelaunch.workingDirectory(dir);
        ck("the new process starts where this one did",
            wd.equals(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()),
            wd.toString());
    }

    /** Arming is what turns a stop into a restart, and it can be refused. */
    static void arming() {
        reset();
        cfg.webRestartRelaunch = false;
        ck("switched off, nothing is armed", !ServerRelaunch.arm("a test"), "");
        ck("...and armed() agrees", !ServerRelaunch.armed(), "");

        cfg.webRestartRelaunch = true;
        ck("switched on, arming works", ServerRelaunch.arm("a test restart"), "");
        ck("...and the reason is kept for the log",
            ServerRelaunch.why().equals("a test restart"), ServerRelaunch.why());
        ck("...arming twice is not an error", ServerRelaunch.arm("again"), "");
        ck("...and does not lose the first reason",
            ServerRelaunch.why().equals("a test restart"), ServerRelaunch.why());

        ServerRelaunch.disarm();
        ck("a stop that is only a stop can disarm it", !ServerRelaunch.armed(), "");
        reset();
    }

    static void launching() throws Exception {
        reset();
        cfg.webRestartRelaunch = true;
        Path marker = dir.resolve("launched.txt");
        cfg.webStartCommand = "touch " + marker.toAbsolutePath();
        ServerRelaunch.Result r = ServerRelaunch.launch(dir);
        ck("launching runs the command", r.ok(), r.message());
        for (int i = 0; i < 50 && !Files.exists(marker); i++) Thread.sleep(100);
        ck("...and the process really ran", Files.exists(marker), "no marker file");

        ServerRelaunch.Result twice = ServerRelaunch.launch(dir);
        ck("a second launch is refused — one restart, one server", !twice.ok(), twice.message());
        cfg.webStartCommand = "";
        reset();
    }

    // ---------- the port claim ----------

    static void lockRoundTrip() {
        WebLock.write(dir, "0.0.0.0", 8246);
        WebLock.Claim c = WebLock.read(dir);
        ck("a claim is written and read back", c != null, "null");
        ck("...naming this process", c != null && c.pid() == ProcessHandle.current().pid(),
            c == null ? "-" : String.valueOf(c.pid()));
        ck("...the port it holds", c != null && c.port() == 8246,
            c == null ? "-" : String.valueOf(c.port()));
        ck("...and where it is running", c != null
            && c.dir().equals(dir.toAbsolutePath().normalize().toString()),
            c == null ? "-" : c.dir());
        ck("...with the start instant that proves the pid is still the same process",
            c != null && c.startedAt() > 0, c == null ? "-" : String.valueOf(c.startedAt()));

        WebLock.clear(dir);
        ck("clearing removes it", WebLock.read(dir) == null, "still there");
        ck("reading a directory with no claim is not an error", WebLock.read(dir) == null, "");
        ck("neither is a null directory", WebLock.read(null) == null, "");
    }

    /** Everything WebLock must refuse to kill. */
    static void refusals() throws Exception {
        ck("no claim at all kills nothing", !WebLock.clearStale(dir, 8246), "");

        // Our own pid: ending it would end the server that is trying to start.
        WebLock.write(dir, "0.0.0.0", 8246);
        ck("it never ends this very process", !WebLock.clearStale(dir, 8246), "");
        ck("...and is still alive to say so", ProcessHandle.current().isAlive(), "");

        // A claim on a different port says nothing about this one.
        claim(dir, 99, nowMs(), 9999, dir.toString());
        ck("a claim on another port is not ours to act on", !WebLock.clearStale(dir, 8246), "");

        // A pid that is gone: the note is stale, but nothing is holding the port.
        Process dead = new ProcessBuilder("/bin/echo", "x").start();
        dead.waitFor();
        claim(dir, dead.pid(), nowMs(), 8246, dir.toString());
        ck("a dead pid is not killed twice", !WebLock.clearStale(dir, 8246), "");
        ck("...and its stale note is cleaned up", WebLock.read(dir) == null, "note survived");

        // A live process that is not java: not something Almin ever started.
        Process sleeper = new ProcessBuilder("/bin/sleep", "60").start();
        claim(dir, sleeper.pid(), startOf(sleeper.pid()), 8246, dir.toString());
        ck("a live non-java process is left alone", !WebLock.clearStale(dir, 8246), "");
        ck("...and is genuinely still running", sleeper.isAlive(), "it was killed");
        sleeper.destroyForcibly();

        // A java process whose recorded start time does not match: pid reuse.
        Process java1 = java();
        claim(dir, java1.pid(), startOf(java1.pid()) - 600_000, 8246, dir.toString());
        ck("a pid whose start time disagrees is a different process now",
            !WebLock.clearStale(dir, 8246), "");
        ck("...so it survives", java1.isAlive(), "it was killed");
        java1.destroyForcibly();

        // A java process from another server directory: someone else's server.
        Process java2 = java();
        claim(dir, java2.pid(), startOf(java2.pid()), 8246, "/some/other/server");
        ck("another server's instance is never killed for our port",
            !WebLock.clearStale(dir, 8246), "");
        ck("...so it survives too", java2.isAlive(), "it was killed");
        java2.destroyForcibly();
        java2.onExit().get();
    }

    /** And the one case where it does act. */
    static void theKill() throws Exception {
        Process leftover = java();
        claim(dir, leftover.pid(), startOf(leftover.pid()), 8246,
            dir.toAbsolutePath().normalize().toString());
        ck("a leftover instance of ours, on our port, is ended",
            WebLock.clearStale(dir, 8246), "it was left running");
        ck("...and is actually gone", !leftover.isAlive(), "still alive");
        ck("...and its claim goes with it", WebLock.read(dir) == null, "note survived");
        if (leftover.isAlive()) leftover.destroyForcibly();
    }

    // ---------- the wiring that makes it happen at the right moment ----------

    static void wiring() throws Exception {
        String almin = src("Almin.java");
        int stopped = almin.indexOf("SERVER_STOPPED");
        int handOver = almin.indexOf("WebUi.handOver()");
        int activity = almin.indexOf("ActivityLog.close()");
        ck("shutdown hands over at all", handOver > 0, "no call");
        ck("...after the logs are flushed, because handing over ends the process",
            activity > 0 && handOver > activity, activity + " vs " + handOver);
        ck("...and it is part of SERVER_STOPPED", stopped > 0 && handOver > stopped, "");

        String web = src("WebUi.java");
        ck("a taken port is reclaimed before the panel gives up its address",
            web.contains("WebLock.clearStale("), "no call");
        ck("...and only after waiting has already failed",
            web.indexOf("WebLock.clearStale(") > web.indexOf("tryPreferred("), "wrong order");
        ck("holding the port is recorded for the next start",
            web.contains("WebLock.write("), "no call");
        int handOverAt = web.indexOf("public static void handOver()");
        int launch = web.indexOf("ServerRelaunch.launch(", handOverAt);
        int halt = web.indexOf("Runtime.getRuntime().halt(0)", launch);
        ck("the new server is started before this one exits",
            handOverAt > 0 && launch > handOverAt && halt > launch,
            handOverAt + " / " + launch + " / " + halt);
        ck("a failed relaunch keeps the panel up to say so",
            web.contains("relaunchError = r.message()"), "not recorded");
        // Without supervisor mode every web thread is a daemon, so "the panel
        // stays up" needs something non-daemon or the JVM exits anyway.
        int hold = web.indexOf("private static void holdOpenToReport()");
        ck("...with a thread that actually holds the JVM open", hold > 0, "missing");
        ck("...which is not a daemon, or it would not",
            hold > 0 && web.indexOf("t.setDaemon(false)", hold) > hold, "daemon");
        ck("...and which gives up eventually rather than squatting the port forever",
            hold > 0 && web.indexOf("REPORT_WINDOW_MS", hold) > hold
                && web.indexOf("halt(0)", hold) > hold, "no limit");
        ck("the page is never cached, so an update is the panel you get",
            web.contains("\"Cache-Control\", \"no-store"), "no header");

        String upd = src("UpdateChecker.java");
        ck("an auto-update restarts rather than only stopping",
            upd.contains("ServerRelaunch.arm(\"an auto-update to "), "no arm");
        ck("...and only falls back to a bare exit when it cannot",
            upd.contains("if (!relaunch) AlminExit.arm("), "unconditional exit");

        String cmd = src("commands/AlminCommand.java");
        ck("/almin op restart restarts too", cmd.contains("ServerRelaunch.arm(\"/almin op restart\")"),
            "no arm");

        String page = src("WebPage.java");
        ck("the page reloads itself onto a new version", page.contains("location.reload()"), "");
        ck("...and waits out the gap instead of calling it an error",
            page.contains("awaitingReturn"), "");
    }

    // ---------- helpers ----------

    static void reset() {
        try {
            Method m = ServerRelaunch.class.getDeclaredMethod("reset");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Process java() throws Exception {
        Path harness = Path.of(System.getProperty("harness", "."));
        Process p = new ProcessBuilder(javaBin, "-cp", harness.toString(), "Sleeper")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        // Give the OS a moment to publish a start instant for it.
        Thread.sleep(300);
        return p;
    }

    static long startOf(long pid) {
        return ProcessHandle.of(pid)
            .flatMap(h -> h.info().startInstant())
            .map(Instant::toEpochMilli).orElse(0L);
    }

    static long nowMs() { return System.currentTimeMillis(); }

    static void claim(Path d, long pid, long startedAt, int port, String where) throws Exception {
        String json = "{\"pid\":" + pid + ",\"startedAt\":" + startedAt + ",\"port\":" + port
            + ",\"bind\":\"0.0.0.0\",\"dir\":\"" + where + "\",\"version\":\"test\"}";
        Files.writeString(d.resolve("config").resolve("almin").resolve("web.lock"), json);
    }

    static String src(String rel) throws Exception {
        return Files.readString(Path.of("src/main/java/com/schecks/almin/" + rel));
    }
}
