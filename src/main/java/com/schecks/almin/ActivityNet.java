package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

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
        if (action != null && action.startsWith("admins")) {
            // "admins on" / "admins off" / "admins temp on" / "admins temp off"
            // / "admins temp clear". Set here rather than by running a command
            // string, so the screen's buttons go through the same re-check
            // every other packet does.
            setAdmins(player, action);
            send(player);
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

    /**
     * Applies an admin-tracking change from the in-game screen.
     *
     * <p>The temporary form is deliberately not written to the config: it is
     * for the afternoon somebody is investigating something, and forgetting by
     * itself at the next restart is the feature.
     */
    private static void setAdmins(ServerPlayer player, String action) {
        String who = player.getGameProfile().name();
        String rest = action.substring("admins".length()).trim();
        boolean temporary = rest.startsWith("temp");
        if (temporary) rest = rest.substring("temp".length()).trim();

        Boolean value = switch (rest) {
            case "on" -> Boolean.TRUE;
            case "off" -> Boolean.FALSE;
            default -> null;
        };
        if (temporary) {
            ActivityLog.setTemporaryIncludeAdmins(value);
            AlminLog.warn("[almin] {} set activity admin tracking to {} for this run",
                who, value == null ? "follow the setting" : value);
            return;
        }
        if (value == null) return;
        AlminConfig.get().activityIncludeAdmins = value;
        AlminConfig.save();
        AlminLog.warn("[almin] {} set activity-include-admins to {}", who, value);
    }

    /** Sends the current log, if the asker is allowed to see it. */
    public static void send(ServerPlayer player) {
        if (!TrustedOps.isTrusted(player.getUUID())) return;
        if (!ServerPlayNetworking.canSend(player, ActivityPayload.TYPE)) return;
        AlminConfig cfg = AlminConfig.get();
        List<ActivityPayload.Track> tracks = new ArrayList<>();
        for (var e : PlayerTracks.everyone(ActivityPayload.MAX_TRACK_POINTS).entrySet()) {
            tracks.add(new ActivityPayload.Track(e.getKey(), e.getValue()));
        }
        ServerPlayNetworking.send(player, new ActivityPayload(
            ActivityLog.recent(ActivityPayload.MAX_ROWS),
            ActivityLog.size(),
            cfg.activityRetentionMinutes,
            cfg.activityLog,
            tracks,
            ActivityLog.adminPolicy()));
    }
}
