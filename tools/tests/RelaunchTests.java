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

        ck("self-relaunch works by default", cfg.webRestartRelaunch,
            String.valueOf(cfg.webRestartRelaunch));
        ck("the entire website outlives a stopped server by default", cfg.webSupervisor,
            String.valueOf(cfg.webSupervisor));
        ck("automatic updates wait for an empty server by default",
            cfg.autoUpdateWhenEmpty, String.valueOf(cfg.autoUpdateWhenEmpty));
        ck("the empty-server policy is configurable",
            AlminConfig.keyByName("auto-update-when-empty") != null, "missing config key");
        cfg.configVersion = 2;
        cfg.webRestartRelaunch = true; // the old persisted default
        Method migrate = AlminConfig.class.getDeclaredMethod("migrate", AlminConfig.class);
        migrate.setAccessible(true);
        migrate.invoke(null, cfg);
        ck("changed restart and website defaults are migrated once",
            cfg.webRestartRelaunch && cfg.webSupervisor && cfg.configVersion == 5,
            cfg.webRestartRelaunch + " / " + cfg.webSupervisor + " / v" + cfg.configVersion);

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
        cfg.webSupervisor = false;
        cfg.webRestartRelaunch = false;
        ck("switched off, nothing is armed", !ServerRelaunch.arm("a test"), "");
        ck("...and armed() agrees", !ServerRelaunch.armed(), "");

        cfg.webSupervisor = true;
        ck("website preservation does not silently take over restarts",
            !ServerRelaunch.arm("a supervised restart"), "armed");
        reset();
        cfg.webSupervisor = false;

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
        System.setProperty("almin.relaunch.startup-timeout-ms", "10000");
        cfg.webRestartRelaunch = true;
        Path marker = dir.resolve("launched.txt");
        cfg.webStartCommand = "touch " + marker.toAbsolutePath()
            + "; printf '%s' \"$ALMIN_RELAUNCH_TOKEN\" > \"$ALMIN_RELAUNCH_READY\"; sleep 1";
        ServerRelaunch.Result r = ServerRelaunch.launch(dir);
        ck("launching waits for a ready replacement", r.ok(), r.message());
        for (int i = 0; i < 50 && !Files.exists(marker); i++) Thread.sleep(100);
        ck("...and the process really ran", Files.exists(marker), "no marker file");

        ServerRelaunch.Result twice = ServerRelaunch.launch(dir);
        ck("a second launch is refused — one restart, one server", !twice.ok(), twice.message());

        reset();
        cfg.webStartCommand = "exit 7";
        ServerRelaunch.Result failed = ServerRelaunch.launch(dir);
        ck("a child that dies during startup is a failed launch",
            !failed.ok() && failed.message().contains("exited during startup"), failed.message());
        ck("a startup failure can be retried from the preserved website",
            !ServerRelaunch.launched(), "launch was latched");
        cfg.webStartCommand = "";
        System.clearProperty("almin.relaunch.startup-timeout-ms");
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
        int started = almin.indexOf("SERVER_STARTED.register(server ->");
        int ready = almin.indexOf("ServerRelaunch.reportReady(");
        int childWeb = almin.indexOf("WebUi.start(server)", ready);
        ck("a replacement reports SERVER_STARTED before trying to take the website",
            started > 0 && ready > started && childWeb > ready,
            started + " / " + ready + " / " + childWeb);
        String relaunch = src("ServerRelaunch.java");
        ck("launch success waits for the child readiness handshake",
            relaunch.contains("awaitReady(p, ready, token)")
                && relaunch.contains("process.isAlive()"), "spawn alone counted as ready");
        // Without supervisor mode every web thread is a daemon, so "the panel
        // stays up" needs something non-daemon or the JVM exits anyway.
        int hold = web.indexOf("private static void holdOpenToReport()");
        ck("...with a thread that actually holds the JVM open", hold > 0, "missing");
        ck("...which is not a daemon, or it would not",
            hold > 0 && web.indexOf("t.setDaemon(false)", hold) > hold, "daemon");
        ck("...and which gives up eventually rather than squatting the port forever",
            hold > 0 && web.indexOf("REPORT_WINDOW_MS", hold) > hold
                && web.indexOf("halt(0)", hold) > hold, "no limit");
        ck("website Stop uses the original supervisor-aware exit watchdog",
            web.contains("AlminExit.arm(\"a stop from the web panel\")")
                && !web.contains("keepPanelForStart"),
            "temporary Stop fallback still present");
        int stoppedMethod = web.indexOf("public static void onServerStopped()");
        int supervisorChoice = web.indexOf("if (!AlminConfig.get().webSupervisor)", stoppedMethod);
        int close = web.indexOf("stop();", supervisorChoice);
        int staysUp = web.indexOf("server stopped — entire website remains available", close);
        ck("the original supervisor path preserves the website without a timeout",
            stoppedMethod > 0 && supervisorChoice > stoppedMethod
                && close > supervisorChoice && staysUp > close,
            stoppedMethod + " / " + supervisorChoice + " / " + close + " / " + staysUp);
        int handoverMethod = web.indexOf("private static void handOverOrStayUp()");
        int spawn = web.indexOf("ServerRelaunch.launch(", handoverMethod);
        int releasePort = web.indexOf("stop();", spawn);
        ck("the original handoff launches before releasing the website",
            spawn > handoverMethod && releasePort > spawn, "launch/stop order changed");
        int startMethod = web.indexOf("private void handOffNow()");
        int startSpawn = web.indexOf("ServerRelaunch.launch(", startMethod);
        int startRelease = web.indexOf("stop();", startSpawn);
        ck("Start uses the same launch-first handoff",
            startSpawn > startMethod && startRelease > startSpawn, "launch/stop order changed");
        ck("the page is never cached, so an update is the panel you get",
            web.contains("\"Cache-Control\", \"no-store"), "no header");

        String upd = src("ServerAutoUpdater.java");
        ck("an auto-update restarts rather than only stopping",
            upd.contains("ServerRelaunch.arm(\"an auto-update to "), "no arm");
        ck("...and only falls back to a bare exit when it cannot",
            upd.contains("if (!relaunch) AlminExit.arm("), "unconditional exit");
        ck("a detected update is queued while players are online",
            upd.contains("shouldWaitForPlayers(server.getPlayerCount())")
                && upd.contains("will install after the last player"), "no empty-server gate");
        String installer = src("ServerJarUpdate.java");
        ck("the download cannot become a loadable duplicate before the final check",
            upd.contains("ServerJarUpdate.stage")
                && upd.indexOf("shouldWaitForPlayers(server.getPlayerCount())")
                    < upd.lastIndexOf("ServerJarUpdate.install"), "jar is swapped too early");
        ck("every server update route uses the BlueMap-safe installer",
            upd.contains("ServerJarUpdate.install")
                && web.contains("ServerJarUpdate.install"), "an update route bypasses staging");
        ck("the updater keeps the running jar pathname stable for background mod scanners",
            installer.contains("Path target = current == null ? releaseTarget : current")
                && installer.contains("ATOMIC_MOVE"), "running jar path can disappear");
        ck("the empty-server queue is checked from the server tick",
            almin.contains("register(ServerAutoUpdater::tick)")
                && almin.contains("ServerAutoUpdater.checkOnBoot(server)"), "not wired");

        Path updateRoot = dir.resolve("update-install");
        Path updateMods = Files.createDirectories(updateRoot.resolve("mods"));
        Path runningJar = updateMods.resolve("almin-2.42.0-server.jar");
        Path stagedJar = updateMods.resolve(".almin-update-almin-2.42.1-server.jar.part");
        Files.writeString(runningJar, "old");
        Files.writeString(stagedJar, "new");
        Class<?> installerType = Class.forName("com.schecks.almin.ServerJarUpdate");
        Method stableInstall = installerType.getDeclaredMethod("install",
            Path.class, Path.class, String.class, Path.class);
        stableInstall.setAccessible(true);
        Object installed = stableInstall.invoke(null, updateRoot, stagedJar,
            "almin-2.42.1-server.jar", runningJar);
        boolean installedOk = (Boolean) installed.getClass().getMethod("ok").invoke(installed);
        Path installedAt = (Path) installed.getClass().getMethod("target").invoke(installed);
        ck("an update replaces bytes without deleting BlueMap's snapshotted path",
            installedOk && installedAt.equals(runningJar.toAbsolutePath().normalize())
                && Files.readString(runningJar).equals("new")
                && !Files.exists(updateMods.resolve("almin-2.42.1-server.jar")),
            String.valueOf(installedAt));

        Method waits = Class.forName("com.schecks.almin.ServerAutoUpdater")
            .getDeclaredMethod("shouldWaitForPlayers", int.class);
        waits.setAccessible(true);
        cfg.autoUpdateWhenEmpty = true;
        ck("the policy waits when somebody is online", (Boolean) waits.invoke(null, 1), "did not wait");
        ck("...and proceeds once nobody is online", !(Boolean) waits.invoke(null, 0), "still waited");
        cfg.autoUpdateWhenEmpty = false;
        ck("the policy can be disabled", !(Boolean) waits.invoke(null, 4), "setting ignored");
        cfg.autoUpdateWhenEmpty = true;

        String cmd = src("commands/AlminCommand.java");
        ck("the in-game update command stages through the same installer",
            cmd.contains("ServerJarUpdate.stage") && cmd.contains("ServerJarUpdate.install"),
            "command update bypasses staging");
        ck("/almin op restart restarts too", cmd.contains("ServerRelaunch.arm(\"/almin op restart\")"),
            "no arm");

        // ---- an update somebody asked for later ----
        // Two conditions that combine: a time, an empty server, or a time
        // after which it waits for one. Nothing about it may install without
        // the people on the server having been told first.
        Class<?> sched = Class.forName("com.schecks.almin.UpdateSchedule");
        Class<?> planType = Class.forName("com.schecks.almin.UpdateSchedule$Plan");
        Constructor<?> plan = planType.getDeclaredConstructor(
            long.class, boolean.class, String.class, String.class, long.class, int.class);
        Method due = planType.getDeclaredMethod("due", long.class, int.class);
        long fourAm = 1_700_000_000_000L;

        Object timed = plan.newInstance(fourAm, false, "2.53.0", "mod", 1L, 0);
        ck("a timed update waits for its time",
            !(Boolean) due.invoke(timed, fourAm - 1000, 0), "went early");
        ck("...and does not care who is online when it comes",
            (Boolean) due.invoke(timed, fourAm, 3), "refused to run");

        Object empty = plan.newInstance(0L, true, "2.53.0", "mod", 1L, 0);
        ck("an empty-server update waits for the last player",
            !(Boolean) due.invoke(empty, fourAm, 1), "kicked somebody");
        ck("...and goes the moment they leave",
            (Boolean) due.invoke(empty, fourAm, 0), "kept waiting");

        Object both = plan.newInstance(fourAm, true, "2.53.0", "mod", 1L, 0);
        ck("both together means the time and then the wait",
            !(Boolean) due.invoke(both, fourAm - 1, 0)
                && !(Boolean) due.invoke(both, fourAm + 1, 2)
                && (Boolean) due.invoke(both, fourAm + 1, 0), "the two conditions do not combine");

        Path schedDir = Files.createDirectories(dir.resolve("sched"));
        Method init = sched.getDeclaredMethod("init", Path.class);
        Method set = sched.getDeclaredMethod("set", planType);
        Method get = sched.getDeclaredMethod("get");
        Method clear = sched.getDeclaredMethod("clear");
        init.invoke(null, schedDir);
        set.invoke(null, both);
        init.invoke(null, schedDir);
        Object read = get.invoke(null);
        ck("a plan survives the restart it is waiting through",
            read != null && read.equals(both), String.valueOf(read));
        clear.invoke(null);
        init.invoke(null, schedDir);
        ck("cancelling it leaves nothing behind",
            get.invoke(null) == null
                && !Files.exists(schedDir.resolve("config").resolve("almin")
                    .resolve("update-schedule.json")), "the file outlived the plan");

        String plan5 = src("UpdateSchedule.java");
        ck("players are told before the server goes",
            plan5.contains("5 * 60_000L") && plan5.contains("broadcastSystemMessage")
                && plan5.contains("The server restarts in"), "no warning");
        ck("...and the plan is cleared before the install that restarts into it",
            plan5.indexOf("plan = null; warned = 0; save();")
                < plan5.indexOf("fire(server, p)"), "a reboot loop is possible");
        ck("what is installed is whatever is newest when it comes due",
            plan5.contains("UpdateChecker.checkAsync()")
                && plan5.contains("installAsked"), "it installs a stale release");
        ck("a check that fails at four in the morning is retried, not dropped",
            plan5.contains("MAX_TRIES") && plan5.contains("RETRY_MS"), "one failure ends it");
        ck("the schedule is offered the server tick",
            almin.contains("register(UpdateSchedule::tick)")
                && almin.contains("UpdateSchedule.init("), "not wired");
        ck("an asked-for update does not consult the auto-update setting",
            upd.contains("!next.asked() && !AlminConfig.get().autoUpdate")
                && upd.contains("!asked && !AlminConfig.get().autoUpdate"),
            "auto-update off would cancel it");
        ck("...and still refuses to kick somebody who joined mid-download",
            upd.contains("if (shouldWait(ready, server.getPlayerCount()))"), "no final check");

        String page = src("WebPage.java");
        ck("the page reloads itself onto a new version", page.contains("location.reload()"), "");
        ck("the panel offers to put an update off",
            page.contains("Install later") && page.contains("action:'schedule'")
                && page.contains("whenEmpty:") && page.contains("nextClock("),
            "no way to schedule one");
        ck("...and to call it off again",
            page.contains("action:'cancel'") && page.contains("Cancel it"), "no way out");
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
