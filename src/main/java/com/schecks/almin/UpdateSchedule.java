package com.schecks.almin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An update somebody asked for later rather than now.
 *
 * <h3>Why later is the useful answer</h3>
 * Installing an update takes the server away and brings it back. Told to do
 * that at four in the afternoon, the honest thing to say is "not now" — and
 * the two answers people actually want are <em>when nobody is on</em> and
 * <em>at a time I picked</em>, which combine into "at four in the morning,
 * and if somebody is still up, once they have gone".
 *
 * <h3>Nobody is surprised by it</h3>
 * A timed update that has an audience says so in chat five minutes before, and
 * again at one minute. There is no version of this that is allowed to drop a
 * server out from under the people on it without warning.
 *
 * <h3>What is stored, and what is decided later</h3>
 * The plan is a time and a condition, not a downloaded jar: the version in it
 * is a label for the panel to show. When it comes due the release is looked up
 * again, so a plan made on Monday installs whatever is newest on Friday rather
 * than something two versions behind. It is written to disk, so a restart in
 * between does not lose it, and it is cleared <em>before</em> the install
 * starts, because an update that reboots into its own trigger would never stop
 * rebooting.
 */
public final class UpdateSchedule {
    private static final Logger CONSOLE = LoggerFactory.getLogger("almin");

    /** Told about it this long before, in chat, if anybody is on to read it. */
    static final long[] WARN_AT_MS = { 5 * 60_000L, 60_000L };

    /** A check that fails at four in the morning is retried, not abandoned. */
    static final long RETRY_MS = 10 * 60_000L;
    static final int MAX_TRIES = 6;

    /**
     * When to install, and what has to be true first.
     *
     * @param at        the instant to install at, or 0 for "as soon as it can"
     * @param whenEmpty wait until nobody is online, however late that makes it
     * @param version   the version this was asked for, as a label
     * @param by        the account that asked
     * @param madeAt    when they asked
     * @param tries     failed attempts so far
     */
    public record Plan(long at, boolean whenEmpty, String version, String by,
                       long madeAt, int tries) {
        /** Whether everything this plan was waiting for has happened. */
        public boolean due(long now, int players) {
            return (at <= 0 || now >= at) && (!whenEmpty || players <= 0);
        }

        /** How this reads in chat and on the console. */
        public String describe() {
            String when = at > 0
                ? "at " + java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    .format(java.time.Instant.ofEpochMilli(at)
                        .atZone(java.time.ZoneId.systemDefault()))
                : "as soon as it can";
            return (version.isEmpty() ? "an update" : "Almin " + version) + " " + when
                + (whenEmpty ? ", once nobody is online" : "");
        }
    }

    private static volatile Path file;
    private static volatile Plan plan;
    /** Which warnings this run has already sent, so five minutes is said once. */
    private static volatile int warned;
    private static volatile boolean firing;

    private UpdateSchedule() {}

    /** The filename, so the file browser can be told to leave it alone. */
    public static String fileName() { return "update-schedule.json"; }

    public static synchronized void init(Path serverDir) {
        file = serverDir.resolve("config").resolve("almin").resolve(fileName());
        plan = null;
        warned = 0;
        firing = false;
        Path f = file;
        if (f == null || !Files.isRegularFile(f)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            JsonObject o = root.getAsJsonObject();
            if (!o.has("at") && !o.has("whenEmpty")) return;
            plan = new Plan(num(o, "at"), o.has("whenEmpty") && o.get("whenEmpty").getAsBoolean(),
                str(o, "version"), str(o, "by"), num(o, "madeAt"), (int) num(o, "tries"));
        } catch (Exception e) {
            AlminLog.warn("[almin] could not read {}: {}", fileName(), e.getMessage());
        }
    }

    /** The plan, or null when nothing is waiting. */
    public static Plan get() { return plan; }

    /** Replaces whatever was planned. One update is waiting at a time. */
    public static synchronized void set(Plan next) {
        plan = next;
        warned = 0;
        save();
        if (next != null) {
            AlminLog.info("[almin] update scheduled: {} (asked by {})",
                next.describe(), next.by().isEmpty() ? "the console" : next.by());
        }
    }

    /** Forgets it. Nothing is downloaded and nothing restarts. */
    public static synchronized void clear() {
        if (plan == null) return;
        AlminLog.info("[almin] scheduled update cancelled ({})", plan.describe());
        plan = null;
        warned = 0;
        save();
    }

    /**
     * Called every server tick; a null check until something is planned.
     *
     * <p>The warning and the install both happen here, on the server thread,
     * because both of them touch the player list.
     */
    public static void tick(MinecraftServer server) {
        Plan p = plan;
        if (p == null || firing || server == null) return;
        long now = System.currentTimeMillis();
        warn(server, p, now);
        if (!p.due(now, server.getPlayerCount())) return;
        firing = true;
        // Cleared before anything is downloaded. The install ends in a
        // restart, and a plan still on disk at that point would be a server
        // that reboots into its own trigger for as long as anybody let it.
        synchronized (UpdateSchedule.class) { plan = null; warned = 0; save(); }
        fire(server, p);
    }

    /** Says so in chat, at each of {@link #WARN_AT_MS}, if anyone is on. */
    private static void warn(MinecraftServer server, Plan p, long now) {
        if (p.at() <= 0 || server.getPlayerCount() <= 0) return;
        long left = p.at() - now;
        for (int i = 0; i < WARN_AT_MS.length; i++) {
            int bit = 1 << i;
            if ((warned & bit) != 0 || left > WARN_AT_MS[i] || left < 0) continue;
            warned |= bit;
            long minutes = Math.max(1, Math.round(WARN_AT_MS[i] / 60000.0));
            String said = "[Almin] The server restarts in " + minutes + " minute"
                + (minutes == 1 ? "" : "s") + " to install "
                + (p.version().isEmpty() ? "an update" : "Almin " + p.version()) + "."
                + (p.whenEmpty() ? " (Or once everybody has logged off, whichever is later.)" : "");
            server.getPlayerList().broadcastSystemMessage(Component.literal(said), false);
            CONSOLE.warn(said);
        }
    }

    /**
     * Looks the release up again and hands it to the installer.
     *
     * <p>Deliberately not the release that was newest when the plan was made:
     * a plan made on Monday should install what is newest on Friday, and if
     * somebody installed it by hand in between there is nothing left to do.
     */
    private static void fire(MinecraftServer server, Plan p) {
        UpdateChecker.checkAsync().thenAccept(result -> server.execute(() -> {
            firing = false;
            switch (result) {
                case UpdateChecker.UpToDate ut -> CONSOLE.warn(
                    "[Almin] The scheduled update is not needed — already on {}.", ut.version());
                case UpdateChecker.CheckFailed cf -> retry(p, cf.reason());
                case UpdateChecker.UpdateAvailable ua -> {
                    if (!ua.release().hasJar()) {
                        retry(p, "release " + ua.release().version() + " has no jar");
                        return;
                    }
                    CONSOLE.warn("[Almin] Installing the scheduled update: {}.",
                        ua.release().version());
                    ServerAutoUpdater.installAsked(server, ua.release(), p.whenEmpty());
                }
            }
        }));
    }

    /** Puts it back with a later time, so one bad minute is not the end of it. */
    private static void retry(Plan p, String why) {
        if (p.tries() + 1 >= MAX_TRIES) {
            CONSOLE.warn("[Almin] Giving up on the scheduled update after {} attempts: {}",
                MAX_TRIES, why);
            AlminLog.warn("[almin] scheduled update abandoned after {} tries: {}", MAX_TRIES, why);
            return;
        }
        long next = System.currentTimeMillis() + RETRY_MS;
        AlminLog.warn("[almin] scheduled update deferred ({}); trying again in {} minutes",
            why, RETRY_MS / 60000);
        set(new Plan(next, p.whenEmpty(), p.version(), p.by(), p.madeAt(), p.tries() + 1));
    }

    private static void save() {
        Path f = file;
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            Plan p = plan;
            if (p == null) { Files.deleteIfExists(f); return; }
            JsonObject o = new JsonObject();
            o.addProperty("format", 1);
            o.addProperty("at", p.at());
            o.addProperty("whenEmpty", p.whenEmpty());
            o.addProperty("version", p.version());
            o.addProperty("by", p.by());
            o.addProperty("madeAt", p.madeAt());
            o.addProperty("tries", p.tries());
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not write {}: {}", fileName(), e.getMessage());
        }
    }

    private static String str(JsonObject o, String field) {
        try {
            return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static long num(JsonObject o, String field) {
        try {
            return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
