package com.schecks.almin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A note the web panel leaves saying which process holds its port.
 *
 * <h3>The problem</h3>
 * A server that crashes, or that halts without its shutdown hooks running, can
 * leave a JVM behind that is doing nothing at all except holding the panel's
 * TCP port. It answers nothing; it just squats. The next server to start then
 * finds its own port taken by its own corpse, moves to a different one, and the
 * address people had bookmarked changes — every time, drifting upwards.
 *
 * <p>Waiting does not help, because nothing is going to release it. The only
 * fix is to end the process that is holding it.
 *
 * <h3>Why this is safe to do</h3>
 * Killing a process because a port is busy would be reckless. This never does
 * that. It ends a process only when <em>every</em> one of these is true:
 *
 * <ul>
 *   <li>Almin itself wrote this note, in this server's own config folder;</li>
 *   <li>the note names this exact port;</li>
 *   <li>the pid in it is alive, and is not us;</li>
 *   <li>that live process started at the instant recorded in the note — so a
 *       recycled pid belonging to something else cannot match;</li>
 *   <li>it is a java process;</li>
 *   <li>it was running from this same server directory.</li>
 * </ul>
 *
 * <p>That last one matters: two Minecraft servers on one host must never be
 * able to kill each other. A panel whose port has been taken by a
 * <em>different</em> server moves aside instead, which is the right answer
 * there. And within one directory there is no ambiguity to begin with —
 * Minecraft's own world lock means a second live server here is impossible, so
 * anything still holding this port is a leftover by definition.
 */
public final class WebLock {
    private static final org.slf4j.Logger CONSOLE = org.slf4j.LoggerFactory.getLogger("almin");

    private static final String FILE = "web.lock";

    /** Clocks and process tables are not exact; a couple of seconds is not drift. */
    private static final long START_SLOP_MS = 2000;

    /** How long a polite request to quit is given before it stops being polite. */
    private static final long TERM_WAIT_MS = 5000;
    private static final long KILL_WAIT_MS = 3000;

    /** What a previous run claimed. */
    public record Claim(long pid, long startedAt, int port, String bind, String dir, String version) {}

    private WebLock() {}

    private static Path file(Path serverDir) {
        return serverDir.resolve("config").resolve("almin").resolve(FILE);
    }

    // ---------- writing ----------

    /** Records that this process holds {@code port}. Never throws. */
    public static void write(Path serverDir, String bind, int port) {
        if (serverDir == null) return;
        try {
            ProcessHandle self = ProcessHandle.current();
            JsonObject o = new JsonObject();
            o.addProperty("pid", self.pid());
            o.addProperty("startedAt", startedAt(self));
            o.addProperty("port", port);
            o.addProperty("bind", bind == null ? "" : bind);
            o.addProperty("dir", serverDir.toAbsolutePath().normalize().toString());
            o.addProperty("version", UpdateChecker.currentVersion());
            Path f = file(serverDir);
            Files.createDirectories(f.getParent());
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // A note we could not leave only costs us the tidy-up next time.
            AlminLog.warn("[almin] could not write the web port lock: {}", e.toString());
        }
    }

    /** Removes our note, once the port is genuinely free again. Never throws. */
    public static void clear(Path serverDir) {
        if (serverDir == null) return;
        try {
            Claim claim = read(serverDir);
            // Only ever delete our own note: another live instance's claim is
            // how it will be found later.
            if (claim != null && claim.pid() != ProcessHandle.current().pid()) return;
            Files.deleteIfExists(file(serverDir));
        } catch (Exception ignored) {
            // Nothing here is worth a message.
        }
    }

    // ---------- reading ----------

    /** The recorded claim, or null if there isn't a readable one. */
    public static Claim read(Path serverDir) {
        if (serverDir == null) return null;
        try {
            Path f = file(serverDir);
            if (!Files.isRegularFile(f)) return null;
            JsonObject o = JsonParser.parseString(
                Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Claim(
                num(o, "pid"),
                num(o, "startedAt"),
                (int) num(o, "port"),
                str(o, "bind"),
                str(o, "dir"),
                str(o, "version"));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- the point of all this ----------

    /**
     * Ends a leftover Almin process that is still holding {@code port}, if that
     * is provably what is happening. Returns true only when something was
     * actually ended, which is the caller's cue to try the bind again.
     */
    public static boolean clearStale(Path serverDir, int port) {
        Claim claim = read(serverDir);
        if (claim == null) return false;
        if (claim.port() != port) return false;
        if (claim.pid() <= 0 || claim.pid() == ProcessHandle.current().pid()) return false;

        Optional<ProcessHandle> found = ProcessHandle.of(claim.pid());
        if (found.isEmpty() || !found.get().isAlive()) {
            // The note outlived the process it described. Nothing to end, and
            // nothing holding the port either — something else has it.
            try { Files.deleteIfExists(file(serverDir)); } catch (IOException ignored) { }
            return false;
        }
        ProcessHandle handle = found.get();
        if (!sameProcess(handle, claim, serverDir)) return false;

        CONSOLE.warn("[almin] Port {} is still held by a leftover Almin process (pid {}) that is "
            + "no longer serving anything. Ending it so the panel keeps its address.",
            port, claim.pid());
        AlminLog.warn("[almin] ending stale web instance pid {} holding port {}", claim.pid(), port);

        if (!end(handle)) {
            CONSOLE.warn("[almin] Could not end pid {} — the panel will move to another port "
                + "for this run.", claim.pid());
            AlminLog.warn("[almin] stale pid {} would not die", claim.pid());
            return false;
        }
        try { Files.deleteIfExists(file(serverDir)); } catch (IOException ignored) { }
        AlminLog.info("[almin] stale web instance pid {} ended; port {} should be free", claim.pid(), port);
        return true;
    }

    /**
     * Whether the live process really is the one the note describes, rather
     * than an unrelated program that happens to have inherited its pid.
     */
    private static boolean sameProcess(ProcessHandle handle, Claim claim, Path serverDir) {
        long started = startedAt(handle);
        if (claim.startedAt() > 0 && started > 0
                && Math.abs(started - claim.startedAt()) > START_SLOP_MS) {
            AlminLog.info("[almin] pid {} is a different process now (started {} not {}) — leaving it alone",
                claim.pid(), started, claim.startedAt());
            return false;
        }
        String command = handle.info().command().orElse("");
        if (!command.isEmpty() && !command.toLowerCase(java.util.Locale.ROOT).contains("java")) {
            AlminLog.info("[almin] pid {} is not a java process ({}) — leaving it alone",
                claim.pid(), command);
            return false;
        }
        String here = serverDir.toAbsolutePath().normalize().toString();
        if (!claim.dir().isEmpty() && !claim.dir().equals(here)) {
            AlminLog.info("[almin] port {} is claimed by a server in {} not {} — leaving it alone",
                claim.port(), claim.dir(), here);
            return false;
        }
        return true;
    }

    /** Asks, then insists. True once the process is gone. */
    private static boolean end(ProcessHandle handle) {
        try {
            handle.destroy();
            if (waitGone(handle, TERM_WAIT_MS)) return true;
            handle.destroyForcibly();
            return waitGone(handle, KILL_WAIT_MS);
        } catch (Exception e) {
            AlminLog.warn("[almin] could not end pid {}: {}", handle.pid(), e.toString());
            return false;
        }
    }

    private static boolean waitGone(ProcessHandle handle, long millis) {
        try {
            handle.onExit().get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !handle.isAlive();
        } catch (Exception e) {
            // Timed out, or we are not allowed to watch it exit.
            return !handle.isAlive();
        }
    }

    private static long startedAt(ProcessHandle handle) {
        try {
            return handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
