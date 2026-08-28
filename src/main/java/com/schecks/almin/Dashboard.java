package com.schecks.almin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.schecks.almin.DashboardPayload.Row;

/**
 * Builds the {@code /almin} dashboard: one snapshot of how the server is
 * doing, who is on it, who has been on it, and what the admin tools are set
 * to. Measuring and formatting both happen here so the modded-client screen
 * and the vanilla-client chat fallback can never drift apart.
 *
 * <p>Everything here is read-only and cheap enough to run on demand, with one
 * exception: the entity count walks every loaded entity in every dimension.
 * That's a per-invocation cost of a command a human types, not a tick cost.
 */
public final class Dashboard {
    private static final int GREEN  = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFCC55;
    private static final int RED    = 0xFFFF6655;
    private static final int PLAIN  = 0;

    /** How many past players the history panel lists before summarising. */
    private static final int HISTORY_ROWS = 12;

    /** How many online players are listed individually before summarising. */
    private static final int ONLINE_ROWS = 30;

    /** Server boot time, for uptime. Set from the SERVER_STARTED hook. */
    private static volatile long startedAt = 0L;

    private Dashboard() {}

    /** Starts the uptime clock. Called once, when the server finishes booting. */
    public static void markStarted() {
        startedAt = System.currentTimeMillis();
    }

    /**
     * The raw measurements behind the dashboard, before any formatting. The row
     * builders and the web panel's stat tiles both read this, so a number shown
     * in game and the same number shown in a browser can never disagree.
     */
    public record Metrics(double tps, float tpsTarget, double mspt,
                          int players, int maxPlayers,
                          long memUsed, long memMax, int memPct,
                          long uptimeMillis, int chunks, long entities, int dimensions) {}

    /** Takes one measurement pass. Must run on the server thread. */
    public static Metrics metrics(MinecraftServer server) {
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        float target = server.tickRateManager().tickrate();
        double tps = mspt <= 0 ? target : Math.min(target, 1000.0 / mspt);

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        int pct = max <= 0 ? 0 : (int) (used * 100 / max);

        int chunks = 0, worlds = 0;
        long entities = 0;
        for (ServerLevel level : server.getAllLevels()) {
            worlds++;
            chunks += level.getChunkSource().getLoadedChunksCount();
            for (Entity ignored : level.getAllEntities()) entities++;
        }
        long uptime = startedAt == 0L ? 0L : System.currentTimeMillis() - startedAt;
        return new Metrics(tps, target, mspt,
            server.getPlayerList().getPlayerCount(), server.getPlayerList().getMaxPlayers(),
            used, max, pct, uptime, chunks, entities, worlds);
    }

    /**
     * Measures everything and renders it into rows for {@code viewer}, which is
     * null when the console asked (no screen, and nothing to gate on).
     */
    public static DashboardPayload build(MinecraftServer server, ServerPlayer viewer) {
        List<Row> rows = new ArrayList<>();
        AlminConfig cfg = AlminConfig.get();
        Metrics m = metrics(server);

        // ---- server ----
        rows.add(Row.header("Server"));
        rows.add(Row.metric("Almin", "v" + UpdateChecker.currentVersion()));
        rows.add(Row.metric("Minecraft", server.getServerVersion()));
        rows.add(Row.metric("Uptime", m.uptimeMillis() == 0L ? "—" : duration(m.uptimeMillis())));
        rows.add(Row.metric("Players", m.players() + " / " + m.maxPlayers()));
        rows.add(Row.metric("Auto-update", cfg.autoUpdate ? "on" : "off",
            cfg.autoUpdate ? PLAIN : YELLOW));

        // ---- performance ----
        rows.add(Row.header("Performance"));
        rows.add(Row.metric("TPS", String.format(Locale.ROOT, "%.2f / %.0f", m.tps(), m.tpsTarget()),
            tpsColour(m.tps(), m.tpsTarget())));
        rows.add(Row.metric("Tick time", String.format(Locale.ROOT, "%.2f ms", m.mspt()),
            msptColour(m.mspt(), m.tpsTarget())));
        if (server.tickRateManager().isFrozen()) {
            rows.add(Row.metric("Tick state", "FROZEN", RED));
        }
        rows.add(Row.metric("Memory",
            bytes(m.memUsed()) + " / " + bytes(m.memMax()) + "  (" + m.memPct() + "%)",
            m.memPct() >= 90 ? RED : m.memPct() >= 75 ? YELLOW : PLAIN));
        rows.add(Row.metric("Loaded chunks", count(m.chunks())));
        rows.add(Row.metric("Entities", count(m.entities())));
        rows.add(Row.metric("Dimensions", String.valueOf(m.dimensions())));
        ServerLevel overworld = server.overworld();
        rows.add(Row.metric("Weather", overworld.isThundering() ? "thunder"
            : overworld.isRaining() ? "rain" : "clear"));

        // ---- who's on right now ----
        PlayerHistory history = PlayerHistory.get(server);
        Map<UUID, PlayerHistory.Entry> all = history.snapshot();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        rows.add(Row.header("Online now" + (players.isEmpty() ? "" : " (" + players.size() + ")")));
        if (players.isEmpty()) {
            rows.add(Row.note("Nobody is connected."));
        } else {
            for (ServerPlayer p : players.subList(0, Math.min(ONLINE_ROWS, players.size()))) {
                String name = p.getGameProfile().name();
                String mask = MaskConfig.maskFor(p.getUUID());
                PlayerHistory.Entry e = all.get(p.getUUID());
                long session = PlayerHistory.sessionLength(p.getUUID());
                long total = (e == null ? 0L : e.playtimeMillis()) + session;
                rows.add(Row.metric(mask == null ? name : name + " (as " + mask + ")",
                    duration(session) + " session · " + duration(total) + " total"));
            }
            if (players.size() > ONLINE_ROWS) {
                rows.add(Row.note("… and " + (players.size() - ONLINE_ROWS) + " more online."));
            }
        }

        // ---- who has been on before ----
        List<Map.Entry<UUID, PlayerHistory.Entry>> past = new ArrayList<>();
        for (Map.Entry<UUID, PlayerHistory.Entry> e : all.entrySet()) {
            if (server.getPlayerList().getPlayer(e.getKey()) == null) past.add(e);
        }
        past.sort(Comparator.comparingLong(
            (Map.Entry<UUID, PlayerHistory.Entry> e) -> e.getValue().lastSeen()).reversed());
        rows.add(Row.header("Player history (" + all.size() + " known)"));
        if (past.isEmpty()) {
            rows.add(Row.note(all.isEmpty()
                ? "No players recorded yet."
                : "Everyone on record is online right now."));
        } else {
            for (Map.Entry<UUID, PlayerHistory.Entry> e : past.subList(0, Math.min(HISTORY_ROWS, past.size()))) {
                PlayerHistory.Entry h = e.getValue();
                String name = h.name().isEmpty() ? e.getKey().toString() : h.name();
                String detail = "last seen " + ago(h.lastSeen())
                    + " · " + h.joins() + (h.joins() == 1 ? " join" : " joins")
                    + " · " + duration(h.playtimeMillis()) + " played";
                rows.add(Row.metric(name, detail));
            }
            if (past.size() > HISTORY_ROWS) {
                rows.add(Row.note("… and " + (past.size() - HISTORY_ROWS) + " more."));
            }
        }

        // ---- admin surface ----
        rows.add(Row.header("Admin"));
        rows.add(Row.metric("Trusted ops", String.valueOf(TrustedOps.count())));
        rows.add(Row.metric("Vanilla ops", String.valueOf(server.getPlayerList().getOps().getUserList().length)));
        rows.add(Row.metric("Masks active", String.valueOf(MaskConfig.snapshot().size())));
        rows.add(Row.metric("Writable roots", cfg.dirWritableRoots));
        rows.add(Row.metric("Spawn immunity",
            cfg.spawnImmunitySeconds <= 0 ? "off" : cfg.spawnImmunitySeconds + "s"));
        rows.add(Row.metric("Log", "config/almin/almin.log"));
        rows.add(Row.metric("Web dashboard",
            WebUi.running() ? "port " + WebUi.port() : cfg.webUiEnabled ? "failed to start" : "off",
            WebUi.running() ? PLAIN : cfg.webUiEnabled ? RED : PLAIN));

        DashboardPayload.Tiles tiles = new DashboardPayload.Tiles(
            m.tps(), m.tpsTarget(), m.memPct(),
            bytes(m.memUsed()) + " / " + bytes(m.memMax()),
            m.players(), m.maxPlayers(),
            m.uptimeMillis() == 0L ? "—" : duration(m.uptimeMillis()));
        return new DashboardPayload(rows, tiles,
            viewer != null && TrustedOps.isTrusted(viewer.getUUID()));
    }

    /**
     * The public tier: a deliberately small, non-sensitive slice for the
     * unauthenticated web view. Version, uptime, a player count (no names), and
     * headline performance — nothing about who plays here, the console, the
     * filesystem, or any setting. Everything an anonymous visitor is allowed to
     * see is chosen here, by hand, rather than filtered out downstream.
     */
    public static List<Row> buildPublic(MinecraftServer server) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.header("Server"));
        rows.add(Row.metric("Almin", "v" + UpdateChecker.currentVersion()));
        rows.add(Row.metric("Minecraft", server.getServerVersion()));
        Metrics m = metrics(server);
        rows.add(Row.metric("Uptime", m.uptimeMillis() == 0L ? "—" : duration(m.uptimeMillis())));
        rows.add(Row.metric("Players online", m.players() + " / " + m.maxPlayers()));

        rows.add(Row.header("Performance"));
        rows.add(Row.metric("TPS", String.format(Locale.ROOT, "%.1f / %.0f", m.tps(), m.tpsTarget()),
            tpsColour(m.tps(), m.tpsTarget())));
        return rows;
    }

    /** Chat rendering of the same rows, for players without the client mod. */
    public static Component toChat(List<Row> rows) {
        MutableComponent out = Component.literal("=== Almin ===\n")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));
        for (Row r : rows) {
            switch (r.kind()) {
                case DashboardPayload.HEADER -> out.append(Component.literal("\n" + r.label() + "\n")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
                case DashboardPayload.NOTE -> out.append(Component.literal("  " + r.label() + "\n")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
                default -> out
                    .append(Component.literal("  " + r.label() + "  ")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
                    .append(Component.literal(r.value() + "\n")
                        .setStyle(r.accent() == PLAIN
                            ? Style.EMPTY.withColor(ChatFormatting.WHITE)
                            : Style.EMPTY.withColor(TextColor.fromRgb(r.accent() & 0xFFFFFF))));
            }
        }
        return out;
    }

    // ---------- formatting ----------

    private static int tpsColour(double tps, float target) {
        if (tps >= target - 0.5) return GREEN;
        return tps >= target * 0.75 ? YELLOW : RED;
    }

    private static int msptColour(double mspt, float target) {
        double budget = 1000.0 / Math.max(target, 1f);
        if (mspt <= budget * 0.6) return GREEN;
        return mspt <= budget ? YELLOW : RED;
    }

    /** "1.4 GB", "812 MB", "4.0 KB". */
    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = b / 1024.0;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    /** Thousands separators, so 12043 chunks reads as 12,043. */
    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** "3d 4h", "2h 14m", "8m", "45s". */
    public static String duration(long millis) {
        if (millis <= 0) return "0s";
        long s = millis / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return s + "s";
    }

    /** "3h ago", "just now", or "unknown" for records with no timestamp. */
    private static String ago(long epochMillis) {
        if (epochMillis <= 0) return "unknown";
        long delta = System.currentTimeMillis() - epochMillis;
        if (delta < 60_000) return "just now";
        return duration(delta) + " ago";
    }
}
