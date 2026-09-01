package com.schecks.almin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Starts the Minecraft server again after Almin has stopped it.
 *
 * <h3>Why this exists</h3>
 * Everything in Almin that "restarts" the server — {@code /almin op restart},
 * the panel's Restart button, an auto-update — did the same one thing: stop the
 * server and let the JVM exit. That is only half a restart. It relies on
 * something outside watching for the exit and starting the server again: a
 * wrapper script, a systemd unit, a host panel's auto-restart. On a server
 * where nothing is watching, every one of those features stops the server and
 * leaves it stopped, and nothing anywhere says that is what happened.
 *
 * <p>So Almin does the second half itself. The command is not something an
 * owner has to work out and type in: this JVM already knows how it was
 * launched, and running that again is a faithful restart — same java binary,
 * same heap flags, same jar, same arguments, same environment, same working
 * directory.
 *
 * <h3>What it will not do</h3>
 * It only ever fires on a stop Almin was asked for. An ordinary {@code /stop},
 * a crash, or a host shutting the machine down are not restarts and must not
 * turn into one, or a server could never be stopped at all.
 *
 * <h3>If something outside is already watching</h3>
 * A host that <em>does</em> have a wrapper watching for the exit would then get
 * two servers: the wrapper's and this one's. In practice the second one to
 * reach the world loses — Minecraft's own {@code session.lock} refuses it — but
 * the noise is real, so {@code web-restart-relaunch} turns this off for hosts
 * that already handle it.
 *
 * @see AlminExit the other half: making sure the process really does end
 */
public final class ServerRelaunch {
    private static final org.slf4j.Logger CONSOLE = org.slf4j.LoggerFactory.getLogger("almin");

    /**
     * Stdin for the new server. Never inherited: this JVM is about to end, and
     * a console reader holding a terminal nobody owns any more gets EIO on its
     * first read — which Minecraft reports as "Exception handling console
     * input". From /dev/null it reads EOF and the reader thread ends quietly.
     */
    private static final ProcessBuilder.Redirect NULL_INPUT =
        ProcessBuilder.Redirect.from(new File("/dev/null"));

    /** Child-to-parent handshake: set only on a process Almin launches. */
    private static final String READY_TOKEN_ENV = "ALMIN_RELAUNCH_TOKEN";
    private static final String READY_FILE_ENV = "ALMIN_RELAUNCH_READY";
    private static final long STARTUP_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long READY_POLL_MS = 100;

    /** How the server would be started again, or why it cannot be. */
    public record Plan(List<String> argv, String display, String source, String problem) {
        public boolean ok() { return problem.isEmpty(); }

        static Plan fail(String why) {
            return new Plan(List.of(), "", "", why);
        }
    }

    /** What happened when the new process was actually launched. */
    public record Result(boolean ok, String message) {}

    private static volatile boolean armed = false;
    private static volatile String why = "";
    private static volatile boolean launched = false;
    private static volatile String lastError = "";

    private ServerRelaunch() {}

    // ---------- the plan ----------

    /**
     * How this server would be started again.
     *
     * <p>An explicit {@code web-start-command} wins: someone typed it, so it is
     * what they meant. Otherwise the command is read back off this very
     * process, which is the only description of how to start this server that
     * cannot be out of date.
     */
    public static Plan plan() {
        AlminConfig cfg = AlminConfig.get();
        String configured = cfg.webStartCommand == null ? "" : cfg.webStartCommand.trim();
        if (!configured.isEmpty()) {
            return new Plan(List.of("/bin/sh", "-c", configured), configured, "web-start-command", "");
        }
        List<String> argv = ownCommandLine();
        if (argv.isEmpty()) {
            return Plan.fail("this JVM does not report its own command line, so Almin cannot "
                + "work out how to start the server again — set web-start-command");
        }
        return new Plan(argv, String.join(" ", argv), "this server's own command line", "");
    }

    /**
     * The command line this JVM was launched with, or an empty list if the
     * platform will not say.
     *
     * <p>{@code ProcessHandle} reads it from the OS (on Linux, /proc), so it is
     * the real argv rather than a reconstruction: heap flags, the launcher jar,
     * {@code nogui} and anything else are all present exactly as given.
     */
    private static volatile List<String> ownArgv;

    private static List<String> ownCommandLine() {
        // A process cannot change how it was started, and the panel asks every
        // few seconds; reading /proc each time would be for nothing.
        List<String> cached = ownArgv;
        if (cached != null) return cached;
        List<String> read = readCommandLine();
        ownArgv = read;
        return read;
    }

    private static List<String> readCommandLine() {
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            Optional<String> command = info.command();
            Optional<String[]> arguments = info.arguments();
            if (command.isEmpty() || arguments.isEmpty()) return List.of();
            String[] args = arguments.get();
            // A java binary with no arguments at all starts nothing; that is not
            // a command line we can restart, it is one we failed to read.
            if (args.length == 0) return List.of();
            List<String> argv = new ArrayList<>(args.length + 1);
            argv.add(command.get());
            argv.addAll(Arrays.asList(args));
            return argv;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The directory the new process should start in.
     *
     * <p>{@code user.dir} rather than the server directory, because the
     * arguments came from a process that was started <em>there</em> — a
     * relative {@code -jar server.jar} only resolves against the same place.
     */
    public static Path workingDirectory(Path serverDirectory) {
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            try {
                Path p = Path.of(userDir);
                if (Files.isDirectory(p)) return p.toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                // fall through to the server directory
            }
        }
        return serverDirectory == null ? Path.of(".") : serverDirectory.toAbsolutePath().normalize();
    }

    // ---------- arming ----------

    /** Whether a relaunch is allowed at all on this server. */
    public static boolean enabled() {
        return AlminConfig.get().webRestartRelaunch;
    }

    /**
     * Marks the coming stop as a restart. Returns false when a relaunch is
     * switched off or impossible here — the caller then falls back to exiting
     * and letting whatever is outside deal with it.
     */
    public static synchronized boolean arm(String reason) {
        if (armed) return true;
        if (!enabled()) {
            AlminLog.info("[almin] relaunch is off (web-restart-relaunch false) — {} will just stop",
                reason);
            return false;
        }
        Plan plan = plan();
        if (!plan.ok()) {
            lastError = plan.problem();
            AlminLog.warn("[almin] cannot relaunch after {}: {}", reason, plan.problem());
            CONSOLE.warn("[almin] {} cannot start the server again: {}", reason, plan.problem());
            return false;
        }
        armed = true;
        why = reason;
        AlminLog.info("[almin] relaunch armed for {} via {}: {}", reason, plan.source(), plan.display());
        return true;
    }

    /** Cancels an armed relaunch (the stop it was for did not happen). */
    public static synchronized void disarm() {
        armed = false;
        why = "";
    }

    public static boolean armed()      { return armed; }
    public static String why()         { return why; }
    public static boolean launched()   { return launched; }
    public static String lastError()   { return lastError; }

    /**
     * Called by the replacement at SERVER_STARTED, immediately before its web
     * listener tries to take the old website's port. A normal server process
     * has no handshake environment and does nothing here.
     */
    public static void reportReady(Path serverDirectory) {
        String token = System.getenv(READY_TOKEN_ENV);
        String named = System.getenv(READY_FILE_ENV);
        if (token == null || token.isBlank() || named == null || named.isBlank()) return;
        try {
            Path root = serverDirectory.toAbsolutePath().normalize();
            Path ready = Path.of(named).toAbsolutePath().normalize();
            // The parent always places this under this server's config folder.
            // Refuse an injected environment path anywhere else.
            if (!ready.startsWith(root.resolve("config").resolve("almin"))) return;
            Files.createDirectories(ready.getParent());
            Path tmp = Files.createTempFile(ready.getParent(), ".relaunch-ready-", ".tmp");
            Files.writeString(tmp, token, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, ready, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, ready, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            CONSOLE.warn("[almin] Could not report replacement-server readiness: {}", e.toString());
        }
    }

    // ---------- doing it ----------

    /**
     * Starts the server again, once.
     *
     * <p>Output is inherited so the new server's log lands wherever this one's
     * did. The existing website is kept intact until the child reaches
     * SERVER_STARTED and reports that through a one-use file handshake. Merely
     * creating a Java process is not success: a child that exits during mod or
     * world loading is a failed launch, and the old website remains available.
     */
    public static synchronized Result launch(Path serverDirectory) {
        if (launched) return new Result(false, "The server has already been started again.");
        Plan plan = plan();
        if (!plan.ok()) {
            lastError = plan.problem();
            return new Result(false, plan.problem());
        }
        Path dir = workingDirectory(serverDirectory);
        Path ready = readinessFile(serverDirectory);
        String token = UUID.randomUUID().toString();
        try {
            Files.deleteIfExists(ready);
            ProcessBuilder pb = new ProcessBuilder(plan.argv());
            pb.directory(dir.toFile());
            pb.redirectInput(NULL_INPUT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.environment().put(READY_TOKEN_ENV, token);
            pb.environment().put(READY_FILE_ENV, ready.toString());
            Process p = pb.start();
            Result started = awaitReady(p, ready, token);
            deleteReady(ready);
            if (!started.ok()) {
                lastError = started.message();
                AlminLog.warn("[almin] replacement server failed before readiness: {}",
                    started.message());
                CONSOLE.warn("[almin] Replacement server did not start: {}", started.message());
                return started;
            }
            launched = true;
            lastError = "";
            AlminLog.info("[almin] replacement server pid {} is ready in {}: {}",
                p.pid(), dir, plan.display());
            CONSOLE.warn("[almin] Replacement server is ready (pid {}).", p.pid());
            return new Result(true, "Replacement server is ready (pid " + p.pid() + ").");
        } catch (Exception e) {
            deleteReady(ready);
            lastError = e.toString();
            AlminLog.warn("[almin] relaunch failed: {}", e.toString());
            CONSOLE.warn("[almin] Could not start the server again: {}", e.toString());
            return new Result(false, "Could not start the server again: " + e);
        }
    }

    private static Path readinessFile(Path serverDirectory) {
        Path root = serverDirectory == null ? Path.of(".") : serverDirectory;
        return root.toAbsolutePath().normalize().resolve("config").resolve("almin")
            .resolve("relaunch.ready");
    }

    private static Result awaitReady(Process process, Path ready, String token) {
        long timeout = Long.getLong("almin.relaunch.startup-timeout-ms", STARTUP_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + Math.max(1000, timeout);
        while (System.currentTimeMillis() < deadline) {
            if (readyToken(ready).equals(token) && process.isAlive()) {
                return new Result(true, "ready");
            }
            if (!process.isAlive()) {
                int code;
                try { code = process.exitValue(); } catch (IllegalThreadStateException e) { code = -1; }
                return new Result(false, "The replacement exited during startup"
                    + (code >= 0 ? " (exit " + code + ")." : "."));
            }
            try {
                Thread.sleep(READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminate(process);
                return new Result(false, "Interrupted while waiting for the replacement to start.");
            }
        }
        terminate(process);
        return new Result(false, "The replacement did not finish starting within "
            + Math.max(1, timeout / 1000) + " seconds.");
    }

    private static String readyToken(Path ready) {
        try {
            return Files.isRegularFile(ready)
                ? Files.readString(ready, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteReady(Path ready) {
        try { Files.deleteIfExists(ready); } catch (IOException ignored) { }
    }

    private static void terminate(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** For tests: forget that a relaunch was armed or done. */
    static synchronized void reset() {
        armed = false;
        launched = false;
        why = "";
        lastError = "";
    }
}
