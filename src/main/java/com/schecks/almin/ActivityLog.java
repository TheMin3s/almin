package com.schecks.almin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What ordinary players did, kept for a while and then thrown away.
 *
 * <p>The third log Almin keeps, and the only one about people rather than the
 * server: {@code ConsoleTap} mirrors Minecraft's own console, {@code AlminLog}
 * records what admins do, and this records what everyone else does — joins,
 * chat, commands, blocks, containers, deaths.
 *
 * <h3>Who is not in it</h3>
 * Anyone who could read it. A player is skipped when their UUID is on the
 * {@link TrustedOps} allowlist, or when they hold moderator permission or
 * above — which covers every vanilla op and everyone {@code /almin config}
 * answers to. So the log is a record of the unprivileged, kept by the
 * privileged, and never a record of the people keeping it.
 *
 * <h3>Why it expires</h3>
 * This is surveillance data about named people, so it has a deliberate
 * shelf life: {@code activity-retention-minutes}, a day by default, after
 * which entries are dropped from memory and from disk. Nothing here is
 * designed to be kept, and there is no export.
 *
 * <h3>Volume</h3>
 * Block edits would otherwise drown everything else, so consecutive edits of
 * the same block type by the same player collapse into one row with a count.
 * The whole log is capped at {@code activity-max-entries}, oldest dropped
 * first, so a busy server cannot grow it without bound.
 *
 * <h3>Threading</h3>
 * {@link #record} is called from the server thread; readers are HTTP threads
 * and the command tree. Everything mutating the deque is synchronized on the
 * class, and disk writes happen on a daemon scheduler so no game tick waits
 * on IO.
 */
public final class ActivityLog {
    /** Same-block edits within this window fold into the previous row. */
    private static final long COALESCE_MS = 30_000;

    /** How often queued rows are appended to disk. */
    private static final long FLUSH_SECONDS = 15;

    /** How often expired rows are dropped and the file rewritten. */
    private static final long PRUNE_SECONDS = 300;

    /** Hard ceiling on one field, so a crafted name or sign can't bloat a row. */
    private static final int MAX_FIELD = 160;

    /** One thing a player did. {@code count} is 1 unless rows were folded. */
    public record Entry(long at, String player, String uuid, String action,
                        String detail, String where, int count) {}

    /** Rows allowed to wait for the writer before the oldest are dropped. */
    private static final int MAX_PENDING = 50_000;

    private static final Deque<Entry> entries = new ArrayDeque<>();
    private static final ConcurrentLinkedQueue<Entry> pending = new ConcurrentLinkedQueue<>();
    /** ConcurrentLinkedQueue.size() walks the list; this does not. */
    private static final java.util.concurrent.atomic.AtomicInteger pendingCount =
        new java.util.concurrent.atomic.AtomicInteger();

    private static volatile Path file;
    private static ScheduledExecutorService scheduler;
    private static boolean shutdownHooked = false;

    private ActivityLog() {}

    // ---------- lifecycle ----------

    /** Loads what survived the last run, then starts the flush/prune timers. */
    public static synchronized void init(MinecraftServer server) {
        if (scheduler != null) return;
        file = server.getServerDirectory().resolve("config").resolve("almin").resolve("activity.log");
        load();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Almin-activity");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(guarded(ActivityLog::flush, "flush"),
            FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(guarded(ActivityLog::prune, "prune"),
            PRUNE_SECONDS, PRUNE_SECONDS, TimeUnit.SECONDS);
        hookShutdown();
    }

    /**
     * Wraps a repeating task so one failure cannot end it.
     *
     * <p>{@code scheduleWithFixedDelay} cancels a task for good the first time
     * it throws, and says nothing. If the flush died that way, rows would queue
     * up in {@link #pending} for the rest of the run with nothing draining
     * them — a slow leak ending in an out-of-memory crash, from a task that had
     * silently stopped hours earlier.
     */
    private static Runnable guarded(Runnable job, String what) {
        return () -> {
            try {
                job.run();
            } catch (Throwable t) {
                AlminLog.warn("[almin] activity {} failed: {}", what, t.toString());
            }
        };
    }

    /**
     * Writes out whatever is queued when the JVM goes down. A crash is exactly
     * when the last few seconds of the log are worth having.
     */
    private static void hookShutdown() {
        if (shutdownHooked) return;
        shutdownHooked = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    flush();
                } catch (Throwable ignored) {
                    // Going down anyway.
                }
            }, "Almin-activity-shutdown"));
        } catch (IllegalStateException ignored) {
            // Already shutting down.
        }
    }

    public static synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        flush();
    }

    // ---------- recording ----------

    /**
     * Whether this player's actions are recorded.
     *
     * <p>False for anyone with the standing to read the log: a trusted UUID, or
     * moderator permission and above, which is every vanilla op.
     */
    public static boolean watched(ServerPlayer player) {
        if (player == null) return false;
        if (TrustedOps.isTrusted(player.getUUID())) return false;
        return !player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
    }

    /** Records one action, if the log is on and this player is watched. */
    public static void record(ServerPlayer player, String action, String detail) {
        if (!AlminConfig.get().activityLog) return;
        if (!watched(player)) return;
        store(player.getGameProfile().name(), player.getUUID().toString(),
            action, detail, where(player), false);
    }

    /**
     * Records a block edit, which is the one action frequent enough to need
     * folding: a player clearing an area produces one row per swing otherwise.
     */
    public static void recordBlock(ServerPlayer player, String action, String block, BlockPos pos) {
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.activityLog || !cfg.activityBlocks) return;
        if (!watched(player)) return;
        store(player.getGameProfile().name(), player.getUUID().toString(),
            action, block, where(player, pos), true);
    }

    /**
     * Stores one row.
     *
     * <p>Split from the two public entry points so the store — folding, the
     * entry cap, expiry, the disk round-trip — can be exercised without a
     * running server behind a {@code ServerPlayer}.
     *
     * @param foldable whether a repeat of the same thing should bump the
     *                 previous row's count instead of adding another
     */
    private static void store(String rawName, String uuid, String action, String rawDetail,
                              String where, boolean foldable) {
        String name = clip(rawName);
        String detail = clip(rawDetail);
        long now = System.currentTimeMillis();
        if (foldable) {
            synchronized (ActivityLog.class) {
                Entry last = entries.peekLast();
                if (last != null && last.action().equals(action) && last.player().equals(name)
                        && last.detail().equals(detail) && now - last.at() < COALESCE_MS) {
                    // Replace the tail with a bumped count. The copy already
                    // queued for disk is superseded at the next prune, which
                    // rewrites the file from memory.
                    entries.pollLast();
                    entries.addLast(new Entry(now, name, last.uuid(), action, detail,
                        where, last.count() + 1));
                    return;
                }
            }
        }
        add(new Entry(now, name, uuid, action, detail, where, 1));
    }

    private static void add(Entry e) {
        synchronized (ActivityLog.class) {
            entries.addLast(e);
            int max = AlminConfig.get().activityMaxEntries;
            while (entries.size() > max) entries.pollFirst();
        }
        // Bounded on purpose. The queue is only a hand-off to the writer, and
        // the deque above is the real log — if the disk is wedged or the
        // writer has stopped, dropping the oldest queued rows is better than
        // growing until the server runs out of memory. The next prune rewrites
        // the file from memory and puts back whatever was skipped.
        if (pendingCount.get() >= MAX_PENDING) {
            if (pending.poll() != null) pendingCount.decrementAndGet();
        }
        pending.add(e);
        pendingCount.incrementAndGet();
    }

    private static String where(ServerPlayer p) {
        return where(p, p.blockPosition());
    }

    private static String where(ServerPlayer p, BlockPos pos) {
        String dim = p.level().dimension().identifier().getPath();
        return dim + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() <= MAX_FIELD ? t : t.substring(0, MAX_FIELD) + "…";
    }

    // ---------- reading ----------

    /** The newest {@code max} rows, newest first. */
    public static synchronized List<Entry> recent(int max) {
        dropExpired();
        List<Entry> out = new ArrayList<>(Math.min(max, entries.size()));
        Iterator<Entry> it = entries.descendingIterator();
        while (it.hasNext() && out.size() < max) out.add(it.next());
        return out;
    }

    public static synchronized int size() {
        dropExpired();
        return entries.size();
    }

    /** Throws the log away now, rather than waiting for it to expire. */
    public static boolean clear() {
        synchronized (ActivityLog.class) {
            entries.clear();
        }
        pending.clear();
        pendingCount.set(0);
        Path f = file;
        if (f == null) return true;
        try {
            Files.deleteIfExists(f);
            return true;
        } catch (IOException e) {
            AlminLog.warn("[almin] could not delete activity.log: {}", e.getMessage());
            return false;
        }
    }

    public static long retentionMillis() {
        return AlminConfig.get().activityRetentionMinutes * 60_000L;
    }

    // ---------- expiry and disk ----------

    /** Caller holds the class monitor. */
    private static void dropExpired() {
        long cutoff = System.currentTimeMillis() - retentionMillis();
        while (!entries.isEmpty() && entries.peekFirst().at() < cutoff) entries.pollFirst();
    }

    /** Appends whatever has been recorded since the last run. */
    private static void flush() {
        Path f = file;
        if (f == null || pending.isEmpty()) return;
        try {
            Files.createDirectories(f.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                Entry e;
                while ((e = pending.poll()) != null) {
                    pendingCount.decrementAndGet();
                    w.write(toJson(e).toString());
                    w.newLine();
                }
            }
        } catch (IOException e) {
            // Losing a batch is better than stalling the server over it, and
            // the in-memory log still has everything until the next restart.
            AlminLog.warn("[almin] could not append to activity.log: {}", e.getMessage());
        }
    }

    /**
     * Drops expired rows and rewrites the file from memory, which is also how
     * folded rows and the entry cap reach disk.
     */
    private static void prune() {
        flush();
        List<Entry> keep;
        synchronized (ActivityLog.class) {
            dropExpired();
            keep = new ArrayList<>(entries);
        }
        Path f = file;
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(f.getParent(), ".activity-", ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Entry e : keep) {
                    w.write(toJson(e).toString());
                    w.newLine();
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not rewrite activity.log: {}", e.getMessage());
        }
    }

    /** Reads back what the last run left, minus anything already expired. */
    private static void load() {
        Path f = file;
        if (f == null || !Files.isRegularFile(f)) return;
        long cutoff = System.currentTimeMillis() - retentionMillis();
        int max = AlminConfig.get().activityMaxEntries;
        try (var lines = Files.lines(f, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                Entry e = fromJson(line);
                if (e == null || e.at() < cutoff) return;
                entries.addLast(e);
                while (entries.size() > max) entries.pollFirst();
            });
        } catch (Exception e) {
            // A truncated or hand-edited file is not worth refusing to start
            // over; whatever parsed is kept and the next prune rewrites it.
            AlminLog.warn("[almin] activity.log partly unreadable: {}", e.getMessage());
        }
    }

    private static JsonObject toJson(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("at", e.at());
        o.addProperty("player", e.player());
        o.addProperty("uuid", e.uuid());
        o.addProperty("action", e.action());
        o.addProperty("detail", e.detail());
        o.addProperty("where", e.where());
        o.addProperty("count", e.count());
        return o;
    }

    private static Entry fromJson(String line) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            return new Entry(
                o.get("at").getAsLong(),
                str(o, "player"), str(o, "uuid"), str(o, "action"),
                str(o, "detail"), str(o, "where"),
                o.has("count") ? o.get("count").getAsInt() : 1);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
