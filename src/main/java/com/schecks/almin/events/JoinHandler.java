package com.schecks.almin.events;

import com.schecks.almin.AlminConfig;
import com.schecks.almin.AlminLog;
import com.schecks.almin.AlminUtil;
import com.schecks.almin.MaskConfig;
import com.schecks.almin.PlayerHistory;
import com.schecks.almin.ServerVersionPayload;
import com.schecks.almin.UpdateChecker;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class JoinHandler {
    private JoinHandler() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            onJoin(server, player);
            AlminUtil.refreshAllTabs(server);
            // Modded clients self-sync; vanilla clients get a chat warning.
            if (ServerPlayNetworking.canSend(player, ServerVersionPayload.TYPE)) {
                ServerPlayNetworking.send(player,
                    new ServerVersionPayload(UpdateChecker.currentVersion()));
            } else {
                sendVanillaClientWarning(player);
            }
            AlminUtil.applySpawnImmunity(player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
            AlminUtil.applySpawnImmunity(newPlayer));
        // Damage cancellation during the window is enforced by
        // ServerPlayerHurtMixin — Fabric's ALLOW_DAMAGE only fires on
        // LivingEntity.hurtServer, which ServerPlayer overrides past.
        // Clean up the per-player timestamp map on disconnect.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            AlminUtil.clearSpawnImmunity(player.getUUID());
            // Bank this session's playtime before the player is gone.
            PlayerHistory.get(server).recordLeave(player.getUUID());
        });
    }

    /** Three chat lines nudging a vanilla client to install the Almin mod. */
    private static void sendVanillaClientWarning(ServerPlayer player) {
        String url = "https://github.com/" + AlminConfig.get().updateRepo + "/releases";
        player.sendSystemMessage(
            Component.literal("[Almin] ").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                .append(Component.literal("This server uses Almin, but your client doesn't have the mod.")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))));
        player.sendSystemMessage(
            Component.literal("Install it from ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                .append(Component.literal(url).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))));
        player.sendSystemMessage(
            Component.literal("You may not be able to join in the future without it.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    private static void onJoin(MinecraftServer server, ServerPlayer player) {
        String name = player.getGameProfile().name();
        // Keeps the name resolvable once the account is offline again, and
        // feeds the player-history panel of /almin.
        PlayerHistory.get(server).recordJoin(player.getUUID(), name);
        // If any active mask was targeting this player's real name, drop it —
        // the real account always wins, so no impersonation at join time.
        MaskConfig.onPlayerJoined(player.getUUID(), name);
        AlminLog.info("[almin] {} joined", name);
    }
}
