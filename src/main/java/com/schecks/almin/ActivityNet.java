package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * Serves the in-game Activity tab.
 *
 * <p>Both directions re-check {@code TrustedOps} on arrival. The log is a
 * record of named people, so an untrusted request is dropped and logged rather
 * than answered.
 */
public final class ActivityNet {
    private ActivityNet() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ActivityRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            String action = payload.action();
            context.server().execute(() -> handle(player, action));
        });
    }

    private static void handle(ServerPlayer player, String action) {
        String who = player.getGameProfile().name();
        if (!TrustedOps.isTrusted(player.getUUID())) {
            AlminLog.warn("[almin] {} asked for the activity log without being a trusted op", who);
            return;
        }
        if ("clear".equals(action)) {
            boolean ok = ActivityLog.clear();
            AlminLog.warn("[almin] {} cleared the activity log ({})", who, ok ? "ok" : "file remained");
            player.sendSystemMessage(Component.literal(ok
                    ? "Activity log cleared."
                    : "Cleared in memory, but activity.log could not be deleted.")
                .setStyle(Style.EMPTY.withColor(ok ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        }
        send(player);
    }

    /** Sends the current log, if the asker is allowed to see it. */
    public static void send(ServerPlayer player) {
        if (!TrustedOps.isTrusted(player.getUUID())) return;
        if (!ServerPlayNetworking.canSend(player, ActivityPayload.TYPE)) return;
        AlminConfig cfg = AlminConfig.get();
        ServerPlayNetworking.send(player, new ActivityPayload(
            ActivityLog.recent(ActivityPayload.MAX_ROWS),
            ActivityLog.size(),
            cfg.activityRetentionMinutes,
            cfg.activityLog));
    }
}
