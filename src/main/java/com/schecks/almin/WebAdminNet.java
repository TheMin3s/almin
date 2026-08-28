package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * Serves the in-game Web tab: status out, password in.
 *
 * <p>Both directions re-check {@code TrustedOps} on arrival. The status packet
 * is only sent to a trusted op, and a password change from anyone else is
 * dropped and logged.
 */
public final class WebAdminNet {
    private WebAdminNet() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(WebAdminRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> sendStatus(player));
        });

        ServerPlayNetworking.registerGlobalReceiver(WebPasswordPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> setPassword(player, payload.password()));
        });
    }

    /** Sends the current panel state, if the asker is allowed to see it. */
    public static void sendStatus(ServerPlayer player) {
        if (!TrustedOps.isTrusted(player.getUUID())) return;
        if (!ServerPlayNetworking.canSend(player, WebAdminPayload.TYPE)) return;
        AlminConfig cfg = AlminConfig.get();
        boolean pwSet = cfg.webAdminPasswordHash != null && !cfg.webAdminPasswordHash.isBlank();
        ServerPlayNetworking.send(player, new WebAdminPayload(
            WebUi.running(),
            WebUi.running() ? WebUi.bind() : cfg.webUiBind,
            WebUi.running() ? WebUi.port() : cfg.webUiPort,
            pwSet,
            cfg.webPublicMetrics,
            cfg.webRequireSecure,
            WebUi.browsableUrl()));
    }

    private static void setPassword(ServerPlayer player, String password) {
        String who = player.getGameProfile().name();
        if (!TrustedOps.isTrusted(player.getUUID())) {
            AlminLog.warn("[almin] {} tried to set the web password without being a trusted op", who);
            return;
        }
        if (password == null || password.length() < 8) {
            player.sendSystemMessage(Component.literal("Password must be at least 8 characters.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return;
        }
        AlminConfig.get().webAdminPasswordHash = Passwords.hash(password);
        AlminConfig.save();
        WebUi.invalidateSessions();
        // The plaintext is never logged — only that it changed, and by whom.
        AlminLog.info("[almin] {} set the web admin password from the in-game panel", who);
        player.sendSystemMessage(Component.literal(
                "Web admin password updated. Existing web logins were signed out.")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        sendStatus(player);
    }
}
