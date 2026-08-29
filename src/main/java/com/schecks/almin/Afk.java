package com.schecks.almin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who has stopped moving.
 *
 * <h3>What it is for</h3>
 * Two things the map needs and could not tell apart before: an action worth a
 * row ("this player went idle here"), and a live flag so the panel's list of
 * who is online can grey out the four people standing in the lobby rather than
 * implying all six are playing.
 *
 * <h3>What counts as still</h3>
 * The block a player is standing in. Turning on the spot, opening a menu and
 * bobbing in water are all "not moving", which is the reading a person means
 * by AFK — and it is the one a server can be sure of, since a client that has
 * stopped sending movement looks exactly like a player who has stopped moving.
 * The threshold is {@code activity-afk-seconds}, twenty by default; 0 turns
 * the whole thing off.
 *
 * <h3>Who is watched</h3>
 * Everyone online is <em>tracked</em>, because the online list shows everyone
 * and greying out half of it by accident would be worse than not greying at
 * all. Only the people the activity log already watches get a <em>row</em>,
 * which is the same rule everything else in the log follows.
 *
 * <h3>Threading</h3>
 * {@link #tick} runs on the server thread; the panel reads from HTTP threads.
 * Everything here is synchronized on the class.
 */
public final class Afk {
    /** Where someone was, when they got there, and whether it is on record. */
    private record Still(long since, String dim, int x, int y, int z, boolean logged) {}

    private static final Map<UUID, Still> seen = new LinkedHashMap<>();
    private static final Map<UUID, String> names = new LinkedHashMap<>();

    /** Checked once a second: idleness is not a thing that needs 20 Hz. */
    private static final int CHECK_TICKS = 20;
    private static int tickCounter = 0;

    private Afk() {}

    /** One online player and whether they have stopped moving. */
    public record Who(String name, String uuid, boolean afk, long stillSince,
                      String dim, int x, int y, int z) {}

    /** Called every tick; does its work once a second. */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < CHECK_TICKS) return;
        tickCounter = 0;
        int seconds = AlminConfig.get().activityAfkSeconds;
        if (seconds <= 0) {
            if (!seen.isEmpty()) clear();
            return;
        }
        long now = System.currentTimeMillis();
        long threshold = seconds * 1000L;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            check(p, now, threshold);
        }
        forgetOffline(server);
    }

    private static synchronized void check(ServerPlayer p, long now, long threshold) {
        UUID id = p.getUUID();
        names.put(id, p.getGameProfile().name());
        String dim = ActivityLog.dimensionOf(p);
        int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
        Still was = seen.get(id);
        if (was == null || was.x() != x || was.y() != y || was.z() != z
                || !was.dim().equals(dim)) {
            // Moved. The clock restarts, and so does the chance to be recorded
            // when they next stop — going idle twice is two events.
            seen.put(id, new Still(now, dim, x, y, z, false));
            return;
        }
        if (was.logged() || now - was.since() < threshold) return;
        seen.put(id, new Still(was.since(), dim, x, y, z, true));
        // The row goes through the ordinary path, so the ordinary rules about
        // who is recorded apply to it too.
        ActivityLog.record(p, "afk", "stopped moving");
    }

    private static synchronized void forgetOffline(MinecraftServer server) {
        if (seen.isEmpty()) return;
        java.util.Set<UUID> online = new java.util.HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getUUID());
        seen.keySet().retainAll(online);
        names.keySet().retainAll(online);
    }

    /** True if this player has been still for longer than the threshold. */
    public static synchronized boolean isAfk(UUID id) {
        Still s = seen.get(id);
        if (s == null || !s.logged()) {
            int seconds = AlminConfig.get().activityAfkSeconds;
            if (s == null || seconds <= 0) return false;
            // Someone excluded from the log never gets a row, so "logged" alone
            // would never be true for them; the clock still says what it says.
            return System.currentTimeMillis() - s.since() >= seconds * 1000L;
        }
        return true;
    }

    /** Everyone online, with where they are and whether they are idle. */
    public static synchronized List<Who> online(MinecraftServer server) {
        List<Who> out = new java.util.ArrayList<>();
        if (server == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            Still s = seen.get(id);
            out.add(new Who(p.getGameProfile().name(), id.toString(), isAfk(id),
                s == null ? 0 : s.since(), ActivityLog.dimensionOf(p),
                p.getBlockX(), p.getBlockY(), p.getBlockZ()));
        }
        return out;
    }

    public static synchronized void clear() {
        seen.clear();
        names.clear();
    }
}
