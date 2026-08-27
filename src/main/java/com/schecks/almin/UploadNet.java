package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Networking for the dir-browser file upload (FileUploadPayload, C2S).
 *
 * The handler re-checks TrustedOps and confines the destination to the
 * folders listed in {@code dir-writable-roots} (plus &lt;level&gt;/datapacks/).
 * A modded client can forge the packet, so neither the sender's trust nor the
 * path is taken on faith.
 */
public final class UploadNet {
    private UploadNet() {}

    /** Registers the C2S upload payload and handler. Call once at init. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().registerLarge(
            FileUploadPayload.TYPE, FileUploadPayload.CODEC, FileUploadPayload.MAX_BYTES + 8192);

        ServerPlayNetworking.registerGlobalReceiver(FileUploadPayload.TYPE, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            // Hop to the server thread before touching the filesystem.
            server.execute(() -> handleUpload(server, player, payload));
        });
    }

    private static void handleUpload(MinecraftServer server, ServerPlayer player, FileUploadPayload payload) {
        if (!TrustedOps.isTrusted(player.getUUID())) {
            reject(player, "you are not a trusted op");
            return;
        }
        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(payload.destPath()).toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || target.getFileName() == null) {
            reject(player, "invalid destination path");
            return;
        }
        Path rel = root.relativize(target);
        boolean underDatapacks = rel.getNameCount() >= 3
            && rel.getName(1).toString().equals("datapacks");
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.dirWritableRootsAsSet().contains(rel.getName(0).toString()) && !underDatapacks) {
            reject(player, "uploads are limited to: " + cfg.dirWritableRoots
                + " or <level>/datapacks/ — change with /almin config dir-writable-roots");
            return;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, ".almin-upload-", ".part");
            Files.write(tmp, payload.data());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            reject(player, "write failed: " + e.getMessage());
            return;
        }
        AlminLog.info("[almin] {} uploaded {} ({} bytes)",
            player.getGameProfile().name(), rel, payload.data().length);
        player.sendSystemMessage(Component.literal("Uploaded ")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
            .append(Component.literal(payload.data().length + " bytes")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
            .append(Component.literal(" -> " + rel)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))));
    }

    private static void reject(ServerPlayer player, String reason) {
        player.sendSystemMessage(Component.literal("Upload rejected — " + reason)
            .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
    }
}
