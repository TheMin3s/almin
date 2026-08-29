package com.schecks.almin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where watched players have been, sampled rather than followed.
 *
 * <p>The activity log records what someone did; this records where they were
 * between those moments, so the two can be drawn together as a path with
 * markers on it. Movement is not an event worth a row each — a player walking
 * for a minute would bury everything else — so it is sampled on a timer and
 * only when they have actually gone somewhere.
 *
 * <h3>What is not kept</h3>
 * The same people the activity log skips: a trusted UUID or anyone with
 * moderator permission is never sampled. Points expire on the same clock as
 * the log ({@code activity-retention-minutes}) and are held in memory only —
 * they are not written to disk, so a restart starts the map fresh. That is a
 * deliberate limit on how long a record of someone's movements can exist.
 *
 * <h3>Threading</h3>
 * {@link #sample} runs on the server thread; readers are HTTP threads.
 * Everything touching the map is synchronized on the class.
 */
public final class PlayerTracks {
    /** One sampled position. */
    public record Point(long at, String dim, int x, int y, int z) {}

    /** Points kept per player. Twenty players at this size is a few MB. */
    private static final int MAX_POINTS = 2000;

    /** Below this many blocks moved, a sample is not worth keeping. */
    private static final int MIN_MOVE = 6;

    private static final Map<UUID, Deque<Point>> tracks = new LinkedHashMap<>();
    private static final Map<UUID, String> names = new LinkedHashMap<>();

    private static int tickCounter = 0;

    private PlayerTracks() {}

    /**
     * Called every tick; samples on its own schedule.
     *
     * <p>A player standing still produces nothing after their first point, and
     * a player running produces one point every {@code activity-track-seconds}.
     */
    public static void sample(MinecraftServer server) {
        AlminConfig cfg = AlminConfig.get();
        int seconds = cfg.activityTrackSeconds;
        if (!cfg.activityLog || seconds <= 0) return;
        if (++tickCounter < seconds * 20) return;
        tickCounter = 0;

        long now = System.currentTimeMillis();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!ActivityLog.watched(p)) continue;
            record(p.getUUID(), p.getGameProfile().name(), new Point(now,
                ActivityLog.dimensionOf(p),
                p.getBlockX(), p.getBlockY(), p.getBlockZ()));
        }
        expire(now - ActivityLog.retentionMillis());
    }

    private static synchronized void record(UUID id, String name, Point point) {
        names.put(id, name);
        Deque<Point> track = tracks.computeIfAbsent(id, k -> new ArrayDeque<>());
        Point last = track.peekLast();
        if (last != null && last.dim().equals(point.dim()) && distance(last, point) < MIN_MOVE) {
            return;
        }
        track.addLast(point);
        while (track.size() > MAX_POINTS) track.pollFirst();
    }

    /** Blocks between two points, on the flat — height changes alone aren't travel. */
    private static double distance(Point a, Point b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static synchronized void expire(long cutoff) {
        tracks.values().forEach(track -> {
            while (!track.isEmpty() && track.peekFirst().at() < cutoff) track.pollFirst();
        });
        tracks.entrySet().removeIf(e -> e.getValue().isEmpty());
        names.keySet().retainAll(tracks.keySet());
    }

    /** The path for one player, oldest first. */
    public static synchronized List<Point> of(UUID id) {
        Deque<Point> track = tracks.get(id);
        return track == null ? List.of() : new ArrayList<>(track);
    }

    /** The path for one player by name, case-insensitively. */
    public static synchronized List<Point> of(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return of(e.getKey());
        }
        return List.of();
    }

    /** Everyone with a path, name to point count. */
    public static synchronized Map<String, Integer> tracked() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Deque<Point>> e : tracks.entrySet()) {
            String name = names.get(e.getKey());
            if (name != null) out.put(name, e.getValue().size());
        }
        return out;
    }

    /** Thrown away with the activity log, since it is the same record. */
    /**
     * Everyone's path at once, thinned to fit a packet.
     *
     * <p>The in-game map draws the same picture as the web one, but it has to
     * arrive over the network first, and full-resolution tracks for a busy
     * server are far past what one packet should carry. Thinning takes every
     * nth point rather than the most recent ones — a shorter path over the
     * whole period is a truer picture than a complete path over the last
     * five minutes.
     *
     * @param budget total points across all players
     */
    public static synchronized Map<String, List<Point>> everyone(int budget) {
        Map<String, List<Point>> out = new LinkedHashMap<>();
        Map<String, Integer> sizes = tracked();
        int total = 0;
        for (int n : sizes.values()) total += n;
        if (total == 0) return out;
        int stride = Math.max(1, (int) Math.ceil(total / (double) Math.max(1, budget)));
        for (String name : sizes.keySet()) {
            List<Point> full = of(name);
            List<Point> thin = new ArrayList<>(full.size() / stride + 2);
            for (int i = 0; i < full.size(); i += stride) thin.add(full.get(i));
            // The last point is where they are now; never drop it.
            if (!full.isEmpty() && thin.get(thin.size() - 1) != full.get(full.size() - 1)) {
                thin.add(full.get(full.size() - 1));
            }
            if (!thin.isEmpty()) out.put(name, thin);
        }
        return out;
    }

    public static synchronized void clear() {
        tracks.clear();
        names.clear();
    }
}
