package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * Serves the in-game Web tab: status out, password and controls in.
 *
 * <p>Every direction re-checks {@code TrustedOps} on arrival. The status packet
 * is only sent to a trusted op, and a password change or control request from
 * anyone else is dropped and logged.
 */
public final class WebAdminNet {
    private WebAdminNet() {}

    /**
     * Settings the Web tab may change.
     *
     * <p>Deliberately not the whole config. Two web keys are missing on
     * purpose: {@code web-admin-password-hash}, which has its own path that
     * never carries a plaintext, and {@code web-start-command}, which is the
     * one setting that becomes a command on the host OS — it stays where it is,
     * behind {@code /almin config} and the config file.
     */
    private static final Set<String> EDITABLE = Set.of(
        "web-ui-enabled",
        "web-ui-port",
        "web-ui-bind",
        "web-public-metrics",
        "web-require-secure",
        "web-session-minutes",
        "web-supervisor"
    );

    /** Changes that only take effect when the listener is rebuilt. */
    private static final Set<String> NEEDS_RESTART = Set.of(
        "web-ui-port", "web-ui-bind", "web-supervisor"
    );

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(WebAdminRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> sendStatus(player));
        });

        ServerPlayNetworking.registerGlobalReceiver(WebPasswordPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> setPassword(player, payload.password()));
        });

        ServerPlayNetworking.registerGlobalReceiver(WebControlPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> control(player, payload));
        });
    }

    /** Sends the current panel state, if the asker is allowed to see it. */
    public static void sendStatus(ServerPlayer player) {
        if (!TrustedOps.isTrusted(player.getUUID())) return;
        if (!ServerPlayNetworking.canSend(player, WebAdminPayload.TYPE)) return;
        AlminConfig cfg = AlminConfig.get();
        boolean pwSet = cfg.webAdminPasswordHash != null && !cfg.webAdminPasswordHash.isBlank();
        boolean startSet = cfg.webStartCommand != null && !cfg.webStartCommand.isBlank();
        ServerPlayNetworking.send(player, new WebAdminPayload(
            WebUi.running(),
            cfg.webUiEnabled,
            WebUi.running() ? WebUi.bind() : cfg.webUiBind,
            WebUi.running() ? WebUi.port() : cfg.webUiPort,
            cfg.webUiPort,
            pwSet,
            cfg.webPublicMetrics,
            cfg.webRequireSecure,
            cfg.webSupervisor,
            startSet,
            cfg.webSessionMinutes,
            WebUi.browsableUrl(),
            WebUi.running() ? "" : WebUi.lastError()));
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

    /** Runs a start/stop/restart, or applies one allowlisted setting. */
    private static void control(ServerPlayer player, WebControlPayload req) {
        String who = player.getGameProfile().name();
        if (!TrustedOps.isTrusted(player.getUUID())) {
            AlminLog.warn("[almin] {} tried to control the web panel without being a trusted op", who);
            return;
        }
        WebUi.Control result = switch (req.action()) {
            case "start"   -> WebUi.startNow();
            case "stop"    -> WebUi.stopNow();
            case "restart" -> WebUi.restartNow();
            case "set"     -> apply(who, req.key(), req.value());
            default        -> new WebUi.Control(false, "Unknown action.");
        };
        if (!"set".equals(req.action())) {
            AlminLog.info("[almin] {} requested web panel {} — {}", who, req.action(), result.message());
        }
        player.sendSystemMessage(Component.literal(result.message())
            .setStyle(Style.EMPTY.withColor(result.ok() ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        sendStatus(player);
    }

    private static WebUi.Control apply(String who, String name, String raw) {
        if (!EDITABLE.contains(name)) {
            AlminLog.warn("[almin] {} tried to set '{}' from the Web tab, which is not editable there", who, name);
            return new WebUi.Control(false, "That setting can't be changed here.");
        }
        AlminConfig.Key key = AlminConfig.keyByName(name);
        if (key == null) return new WebUi.Control(false, "Unknown setting.");
        Object parsed;
        try {
            parsed = key.parse(raw);
        } catch (IllegalArgumentException e) {
            return new WebUi.Control(false, name + ": " + e.getMessage());
        }
        AlminConfig cfg = AlminConfig.get();
        key.setter.accept(cfg, parsed);
        AlminConfig.save();
        AlminLog.info("[almin] {} set {} = {} from the Web tab", who, name, parsed);

        // Turning the panel off should actually stop it, and turning it on
        // should actually start it — not wait for the next server restart.
        if (name.equals("web-ui-enabled")) {
            return Boolean.TRUE.equals(parsed) ? WebUi.startNow() : WebUi.stopNow();
        }
        if (NEEDS_RESTART.contains(name) && WebUi.running()) {
            WebUi.Control r = WebUi.restartNow();
            return r.ok() ? new WebUi.Control(true, name + " updated. " + r.message())
                          : new WebUi.Control(false, name + " saved, but the panel didn't come back: " + r.message());
        }
        return new WebUi.Control(true, name + " set to " + parsed + ".");
    }
}
