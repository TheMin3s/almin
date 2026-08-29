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
 * the log ({@code activity-retention-minutes}), which is the limit on how long
 * a record of someone's movements exists.
 *
 * <p>They used to be held in memory only, which sounded like a privacy measure
 * and was really an inconsistency: every row in the activity log carries the
 * coordinates it happened at and is written to disk, so a restart threw away
 * the paths and kept the places. It also meant a player who was offline had no
 * path at all, which is exactly when you want one. They are written now, on
 * the same clock and with the same expiry.
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

    /** Where the paths are kept between runs. */
    private static volatile java.nio.file.Path file;
    /** Set when something has changed since the last write. */
    private static boolean dirty = false;
    /** Ticks between writes, so a busy server is not a busy disk. */
    private static int sinceSave = 0;
    private static final int SAVE_TICKS = 20 * 60;

    private PlayerTracks() {}

    /** Picks up what the last run recorded. Call once at server start. */
    public static synchronized void init(MinecraftServer server) {
        file = server.getServerDirectory().resolve("config").resolve("almin")
            .resolve("tracks.json");
        load();
        expire(System.currentTimeMillis() - ActivityLog.retentionMillis());
    }

    /** Writes them out, if anything has changed. Called at shutdown too. */
    public static synchronized void save() {
        java.nio.file.Path f = file;
        if (f == null || !dirty) return;
        try {
            java.nio.file.Files.createDirectories(f.getParent());
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            for (Map.Entry<UUID, Deque<Point>> e : tracks.entrySet()) {
                String name = names.get(e.getKey());
                if (name == null) continue;
                com.google.gson.JsonObject who = new com.google.gson.JsonObject();
                who.addProperty("name", name);
                com.google.gson.JsonArray pts = new com.google.gson.JsonArray();
                for (Point p : e.getValue()) {
                    // One array per point rather than an object: this is the
                    // largest file Almin writes and the keys would be most of
                    // it. Order is at, dim, x, y, z.
                    com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                    a.add(p.at());
                    a.add(p.dim());
                    a.add(p.x());
                    a.add(p.y());
                    a.add(p.z());
                    pts.add(a);
                }
                who.add("points", pts);
                root.add(e.getKey().toString(), who);
            }
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile(
                f.getParent(), ".tracks-", ".tmp");
            java.nio.file.Files.writeString(tmp, root.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(tmp, f,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (Exception e) {
            AlminLog.warn("[almin] could not write tracks.json: {}", e.toString());
        }
    }

    private static void load() {
        java.nio.file.Path f = file;
        if (f == null || !java.nio.file.Files.exists(f)) return;
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(
                java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
            for (String key : root.keySet()) {
                UUID id;
                try { id = UUID.fromString(key); }
                catch (IllegalArgumentException bad) { continue; }
                com.google.gson.JsonObject who = root.getAsJsonObject(key);
                String name = who.has("name") ? who.get("name").getAsString() : "";
                if (name.isEmpty()) continue;
                Deque<Point> track = new ArrayDeque<>();
                for (var el : who.getAsJsonArray("points")) {
                    var a = el.getAsJsonArray();
                    if (a.size() < 5) continue;
                    track.addLast(new Point(a.get(0).getAsLong(), a.get(1).getAsString(),
                        a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsInt()));
                    if (track.size() > MAX_POINTS) track.pollFirst();
                }
                if (track.isEmpty()) continue;
                tracks.put(id, track);
                names.put(id, name);
            }
            AlminLog.info("[almin] picked up paths for {} player(s)", tracks.size());
        } catch (Exception e) {
            AlminLog.warn("[almin] tracks.json unreadable ({}), starting fresh", e.getMessage());
        }
    }

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
        if (++sinceSave >= SAVE_TICKS / Math.max(1, seconds)) {
            sinceSave = 0;
            save();
        }
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
        dirty = true;
    }

    /** Blocks between two points, on the flat — height changes alone aren't travel. */
    private static double distance(Point a, Point b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static synchronized void expire(long cutoff) {
        int before = tracks.size();
        tracks.values().forEach(track -> {
            while (!track.isEmpty() && track.peekFirst().at() < cutoff) track.pollFirst();
        });
        tracks.entrySet().removeIf(e -> e.getValue().isEmpty());
        names.keySet().retainAll(tracks.keySet());
        if (tracks.size() != before) dirty = true;
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

    /** The UUID behind a tracked name, or null — the map draws faces from it. */
    public static synchronized UUID uuidOf(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
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
        dirty = true;
        java.nio.file.Path f = file;
        if (f == null) return;
        try {
            java.nio.file.Files.deleteIfExists(f);
            dirty = false;
        } catch (java.io.IOException e) {
            AlminLog.warn("[almin] could not delete tracks.json: {}", e.getMessage());
        }
    }
}
