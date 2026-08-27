package com.schecks.almin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AlminUtil {
    private AlminUtil() {}

    /**
     * Resolves a player name to a NameAndId record.
     *
     * Order of lookup:
     *  1. Online ServerPlayer via PlayerList.getPlayerByName
     *  2. Cached UUID + last known name in PlayerHistory (covers offline players we've seen)
     *
     * Returns null if the player has never joined this server.
     */
    public static NameAndId resolveNameAndId(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return new NameAndId(online.getUUID(), online.getGameProfile().name());
        }
        UUID cached = PlayerHistory.get(server).findByName(name);
        if (cached != null) {
            String cachedName = PlayerHistory.get(server).nameOf(cached);
            return new NameAndId(cached, cachedName.isEmpty() ? name : cachedName);
        }
        return null;
    }

    /**
     * Per-player spawn-immunity expiry (system-millis). Implemented as a
     * timestamp map + a {@code hurtServer} hook in {@code ServerPlayerHurtMixin}
     * so the immunity covers everything (fire/lava/drown included) and never
     * appears in the effects list — there's no MobEffect involved at all.
     * Applied on join and respawn; no effect when the config is 0.
     */
    private static final Map<UUID, Long> SPAWN_IMMUNE_UNTIL = new ConcurrentHashMap<>();

    public static void applySpawnImmunity(ServerPlayer player) {
        int secs = AlminConfig.get().spawnImmunitySeconds;
        if (secs <= 0) return;
        SPAWN_IMMUNE_UNTIL.put(player.getUUID(), System.currentTimeMillis() + secs * 1000L);
    }

    /** True while {@code id} is within their spawn-immunity window. */
    public static boolean isSpawnImmune(UUID id) {
        Long until = SPAWN_IMMUNE_UNTIL.get(id);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        SPAWN_IMMUNE_UNTIL.remove(id);
        return false;
    }

    /** Forgets any pending immunity for a player — e.g. on disconnect. */
    public static void clearSpawnImmunity(UUID id) {
        SPAWN_IMMUNE_UNTIL.remove(id);
    }

    /**
     * Re-sends every player's tab-list display name. Used after a mask changes,
     * so the new name shows up without a reconnect.
     */
    public static void refreshAllTabs(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            broadcastTabPacket(server, p);
        }
    }

    private static void broadcastTabPacket(MinecraftServer server, ServerPlayer player) {
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
            List.of(player)
        );
        server.getPlayerList().broadcastAll(packet);
    }
}
