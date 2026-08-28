package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Networking for the modded-client nano editor:
 *  - NanoOpenPayload (S2C): server pushes a file's contents into the editor.
 *  - NanoSavePayload (C2S): client sends edited contents back to be written.
 *
 * Both are registered as "large" payloads so a sizable file survives the
 * serverbound packet-size limit. The C2S handler re-checks TrustedOps and
 * confines writes to the server directory — see NanoSupport.saveFromEditor.
 */
public final class NanoNet {
    private NanoNet() {}

    /** Registers both nano payloads and the C2S save handler. Call once at init. */
    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(NanoSavePayload.TYPE, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            // Hop to the server thread before touching the filesystem.
            server.execute(() -> handleSave(server, player, payload));
        });
    }

    private static void handleSave(MinecraftServer server, ServerPlayer player, NanoSavePayload payload) {
        NanoSupport.Result result = NanoSupport.saveFromEditor(
            server, player, payload.path(), payload.content());
        switch (result.kind) {
            case "ok" -> {
                AlminLog.info("[almin] {} saved {} ({} chars) via nano editor",
                    player.getGameProfile().name(),
                    result.serverRoot.relativize(result.target), result.bytes);
                player.sendSystemMessage(Component.literal("Saved ")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                    .append(Component.literal(result.bytes + " chars")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                    .append(Component.literal(" -> " + result.serverRoot.relativize(result.target))
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))));
            }
            case "denied" -> player.sendSystemMessage(Component.literal(
                "nano save denied — you are not a trusted op.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            case "escape" -> player.sendSystemMessage(Component.literal(
                "nano save rejected — path escapes the server directory.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            case "io" -> player.sendSystemMessage(Component.literal(
                "nano save failed: " + result.message)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            default -> player.sendSystemMessage(Component.literal(
                "nano save failed (" + result.kind + ").")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
        }
    }
}
