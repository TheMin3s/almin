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
import java.util.function.Predicate;

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

    /** Rows allowed to wait for the writer before the oldest are dropped. */
    private static final int MAX_PENDING = 50_000;

    private static final Deque<ActivityEntry> entries = new ArrayDeque<>();
    private static final ConcurrentLinkedQueue<ActivityEntry> pending = new ConcurrentLinkedQueue<>();
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
     * Set for this run only, overriding {@code activity-include-admins}.
     *
     * <p>Null means "follow the setting". A deliberately un-persisted field:
     * the reason to record admins is usually a single afternoon — a grief
     * investigation, a new moderator being shown the ropes — and a switch you
     * have to remember to turn back off is one that stays on. This one
     * forgets by itself at the next restart.
     */
    private static volatile Boolean temporaryIncludeAdmins = null;

    /**
     * Whether admins are being recorded, and why.
     *
     * <p>{@code temporary} is true when the answer comes from the run-only
     * override rather than the saved setting.
     */
    public static ActivityAdminPolicy adminPolicy() {
        Boolean t = temporaryIncludeAdmins;
        boolean configured = AlminConfig.get().activityIncludeAdmins;
        return t == null
            ? new ActivityAdminPolicy(configured, false, configured)
            : new ActivityAdminPolicy(t, true, configured);
    }

    /** Whether admins are recorded right now, however that was decided. */
    public static boolean includeAdmins() {
        return adminPolicy().includeAdmins();
    }

    /**
     * Overrides the setting until the server restarts. {@code null} hands
     * control back to {@code activity-include-admins}.
     */
    public static void setTemporaryIncludeAdmins(Boolean value) {
        temporaryIncludeAdmins = value;
        AlminLog.info("[almin] activity admin tracking set to {} for this run",
            value == null ? "follow the setting" : value);
    }

    /**
     * Whether this player's actions are recorded.
     *
     * <p>By default, false for anyone with the standing to read the log: a
     * trusted UUID, or moderator permission and above, which is every vanilla
     * op. That keeps the log a record of the unprivileged kept by the
     * privileged, rather than a way for staff to watch each other.
     *
     * <p>{@code activity-include-admins} — or the run-only override — drops
     * that exemption, for a server that wants a complete record.
     */
    public static boolean watched(ServerPlayer player) {
        if (player == null) return false;
        if (includeAdmins()) return true;
        return !isAdmin(player);
    }

    /**
     * Whether this is an admin driving Almin itself.
     *
     * <p>Never recorded, and not subject to {@code activity-include-admins}
     * either. The log is read through {@code /almin}, so recording those
     * commands means every admin who opens the activity screen writes a row
     * about having opened it — the log fills with the act of looking at it,
     * and the thing it exists to show gets pushed off the end. Turning admin
     * tracking on is a choice to watch what admins <em>do in the world</em>,
     * not to watch them read.
     *
     * <p>An ordinary player typing {@code /almin} is still recorded: that is
     * someone finding the tool, which is worth seeing.
     */
    private static boolean ownCommand(ServerPlayer player, String action, String detail) {
        return isOwnCommand(action, detail) && isAdmin(player);
    }

    /**
     * The string half of the rule above, without a player.
     *
     * <p>Deliberately exact: {@code /alminx} and {@code /admin} are somebody
     * else's commands and stay in the log.
     */
    static boolean isOwnCommand(String action, String detail) {
        if (!"command".equals(action) || detail == null) return false;
        String typed = detail.trim();
        if (typed.startsWith("/")) typed = typed.substring(1).trim();
        return typed.equals(Almin.MOD_ID) || typed.startsWith(Almin.MOD_ID + " ");
    }

    /** Trusted UUID, or moderator permission and above — every vanilla op. */
    private static boolean isAdmin(ServerPlayer player) {
        if (player == null) return false;
        if (TrustedOps.isTrusted(player.getUUID())) return true;
        return player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
    }

    /** Records one action, if the log is on and this player is watched. */
    public static void record(ServerPlayer player, String action, String detail) {
        record(player, action, detail, false);
    }

    /**
     * As above, but folding a repeat of the same thing into the previous row.
     * Used for anything a player can do many times a second — damage ticks,
     * eating, clicking an entity.
     */
    public static void recordFolded(ServerPlayer player, String action, String detail) {
        record(player, action, detail, folds(action));
    }

    private static void record(ServerPlayer player, String action, String detail, boolean fold) {
        if (!AlminConfig.get().activityLog) return;
        if (ownCommand(player, action, detail)) return;
        if (!watched(player)) return;
        store(player.getGameProfile().name(), player.getUUID().toString(),
            action, detail, player, player.blockPosition(), fold);
    }

    /**
     * Actions that are always kept one row per thing that happened.
     *
     * <p>Folding replaces the previous row with a count and moves it to where
     * the latest one was, which is fine for "ate nine steaks" and destroys the
     * only interesting thing about a block edit: <em>where</em> it was. A wall
     * of twenty-five planks was arriving as two or three rows with a count on
     * them, so the isometric view drew two or three cubes and the shape of
     * what somebody built was simply not in the log any more.
     *
     * <p>The same goes for a fight. Nine swings in one place and nine swings
     * spread over forty blocks are different events, and a count cannot tell
     * them apart.
     *
     * <p>Everything else still folds. Eating, clicking an entity and using an
     * item happen many times a second and are not worth a row each.
     */
    private static final java.util.Set<String> NEVER_FOLDED =
        java.util.Set.of("place", "break", "attack", "hurt", "kill", "sign");

    static boolean folds(String action) { return !NEVER_FOLDED.contains(action); }

    /**
     * Records a block edit, at the block rather than at the player.
     *
     * <p>Placements and breaks are never folded — see {@link #NEVER_FOLDED}.
     * Uses still are: right-clicking a chest forty times is one fact.
     */
    public static void recordBlock(ServerPlayer player, String action, String block, BlockPos pos) {
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.activityLog || !cfg.activityBlocks) return;
        if (!watched(player)) return;
        store(player.getGameProfile().name(), player.getUUID().toString(),
            action, block, player, pos, folds(action));
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
                              ServerPlayer at, BlockPos pos, boolean foldable) {
        store(rawName, uuid, action, rawDetail, dimensionOf(at),
            pos.getX(), pos.getY(), pos.getZ(), foldable);
    }

    private static void store(String rawName, String uuid, String action, String rawDetail,
                              String dim, int x, int y, int z, boolean foldable) {
        String name = clip(rawName);
        String detail = clip(rawDetail);
        long now = System.currentTimeMillis();
        if (foldable) {
            synchronized (ActivityLog.class) {
                ActivityEntry last = entries.peekLast();
                if (last != null && last.action().equals(action) && last.player().equals(name)
                        && last.detail().equals(detail) && now - last.at() < COALESCE_MS) {
                    // Replace the tail with a bumped count, moved to where it
                    // last happened. The copy already queued for disk is
                    // superseded at the next prune, which rewrites from memory.
                    entries.pollLast();
                    entries.addLast(new ActivityEntry(now, name, last.uuid(), action, detail,
                        dim, x, y, z, last.count() + 1));
                    return;
                }
            }
        }
        add(new ActivityEntry(now, name, uuid, action, detail, dim, x, y, z, 1));
    }

    private static void add(ActivityEntry e) {
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

    /** The short dimension name, e.g. "overworld" or "the_nether". */
    public static String dimensionOf(ServerPlayer p) {
        try {
            return p.level().dimension().identifier().getPath();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() <= MAX_FIELD ? t : t.substring(0, MAX_FIELD) + "…";
    }

    // ---------- reading ----------

    /** The newest {@code max} rows, newest first. */
    public static synchronized List<ActivityEntry> recent(int max) {
        dropExpired();
        List<ActivityEntry> out = new ArrayList<>(Math.min(max, entries.size()));
        Iterator<ActivityEntry> it = entries.descendingIterator();
        while (it.hasNext() && out.size() < max) out.add(it.next());
        return out;
    }

    /** One window into the log, and how much there was to take it from. */
    public record Page(List<ActivityEntry> rows, int matched, boolean more) {}

    /**
     * A window into the log, newest first.
     *
     * <p>{@link #recent} answers "the newest N", which is the whole log only
     * while the log is shorter than N — and the menu reading it had no way
     * to ask for the rest. Every setting that sounded like it would help
     * ("the amount of activity log entries") governs how much is <em>kept</em>,
     * so raising them made the list no longer, and said nothing about why.
     * The list pages now, so the number it stops at is where somebody has
     * scrolled to rather than a ceiling they have to find and lift.
     *
     * <p>{@code keep} runs before the skip, so paging a filtered log walks the
     * matches rather than the rows: page two of "steve" is the next twenty
     * things Steve did, not whatever is left after skipping twenty rows that
     * were mostly somebody else's.
     *
     * @param skip how many matching rows to pass over, newest first
     * @param max  how many to return after that
     * @param keep which rows count at all, or null for every one of them
     */
    public static synchronized Page page(int skip, int max, Predicate<ActivityEntry> keep) {
        dropExpired();
        int want = Math.max(0, max);
        int past = Math.max(0, skip);
        List<ActivityEntry> out = new ArrayList<>(Math.min(want, entries.size()));
        int matched = 0;
        Iterator<ActivityEntry> it = entries.descendingIterator();
        while (it.hasNext()) {
            ActivityEntry e = it.next();
            if (keep != null && !keep.test(e)) continue;
            if (matched >= past && out.size() < want) out.add(e);
            matched++;
        }
        return new Page(out, matched, matched > past + out.size());
    }

    public static synchronized int size() {
        dropExpired();
        return entries.size();
    }

    /**
     * Throws the log away now, rather than waiting for it to expire.
     *
     * <p>The map is the same record seen another way, so it goes too — the
     * paths, and the pictures of the ground they were drawn over. Use
     * {@link #wipe()} to take only the rows.
     */
    public static boolean clear() {
        boolean ok = wipe();
        PlayerTracks.clear();
        WorldSnapshots.clear();
        return ok;
    }

    /** The rows and nothing else, for a panel that offers the three separately. */
    public static boolean wipe() {
        synchronized (ActivityLog.class) {
            entries.clear();
        }
        Afk.clear();
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
                ActivityEntry e;
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
        List<ActivityEntry> keep;
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
                for (ActivityEntry e : keep) {
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
                ActivityEntry e = fromJson(line);
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

    private static JsonObject toJson(ActivityEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("at", e.at());
        o.addProperty("player", e.player());
        o.addProperty("uuid", e.uuid());
        o.addProperty("action", e.action());
        o.addProperty("detail", e.detail());
        o.addProperty("dim", e.dim());
        o.addProperty("x", e.x());
        o.addProperty("y", e.y());
        o.addProperty("z", e.z());
        o.addProperty("count", e.count());
        return o;
    }

    private static ActivityEntry fromJson(String line) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            // Rows written before the place became numbers carry a "where"
            // string instead; read what can be read and keep the rest.
            String dim = str(o, "dim");
            int x = num(o, "x"), y = num(o, "y"), z = num(o, "z");
            if (dim.isEmpty() && o.has("where")) {
                String[] parts = str(o, "where").split(" ");
                if (parts.length == 2) {
                    dim = parts[0];
                    String[] xyz = parts[1].split(",");
                    if (xyz.length == 3) {
                        x = parseOr(xyz[0], 0);
                        y = parseOr(xyz[1], 0);
                        z = parseOr(xyz[2], 0);
                    }
                }
            }
            return new ActivityEntry(
                o.get("at").getAsLong(),
                str(o, "player"), str(o, "uuid"), str(o, "action"), str(o, "detail"),
                dim, x, y, z,
                o.has("count") ? o.get("count").getAsInt() : 1);
        } catch (Exception e) {
            return null;
        }
    }

    private static int num(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
