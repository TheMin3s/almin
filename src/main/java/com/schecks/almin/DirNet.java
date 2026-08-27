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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Networking for the modded-client file browser:
 *  - DirListingPayload (S2C): server sends a directory's contents.
 *  - DirRequestPayload (C2S): client asks to list a directory.
 *
 * The C2S handler re-checks TrustedOps and confines every path to the server
 * directory — a modded client can forge the request, so nothing is trusted.
 */
public final class DirNet {
    private DirNet() {}

    /** Registers both payloads and the C2S request handler. Call once at init. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(DirListingPayload.TYPE, DirListingPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DirRequestPayload.TYPE, DirRequestPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(DirRequestPayload.TYPE, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            // Hop to the server thread before touching the filesystem.
            server.execute(() -> sendListing(server, player, payload.path()));
        });
    }

    /**
     * Resolves {@code relativePath} under the server directory, lists it, and
     * sends a DirListingPayload back to {@code player}. Silently does nothing
     * if the player is not a trusted op; reports bad paths in chat.
     */
    public static void sendListing(MinecraftServer server, ServerPlayer player, String relativePath) {
        if (!TrustedOps.isTrusted(player.getUUID())) return;

        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).toAbsolutePath().normalize();
        if (!target.startsWith(root) || !Files.isDirectory(target)) {
            player.sendSystemMessage(Component.literal("No such directory: "
                    + (relativePath.isEmpty() ? "." : relativePath))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return;
        }

        List<Path> paths = new ArrayList<>();
        try (Stream<Path> s = Files.list(target)) {
            s.forEach(paths::add);
        } catch (IOException e) {
            player.sendSystemMessage(Component.literal("Directory list failed: " + e.getMessage())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return;
        }
        paths.sort(Comparator
            .comparing((Path p) -> !Files.isDirectory(p))            // dirs first
            .thenComparing(p -> p.getFileName().toString().toLowerCase()));

        List<DirListingPayload.Entry> entries = new ArrayList<>();
        for (Path p : paths) {
            if (entries.size() >= DirListingPayload.MAX_ENTRIES) break;
            boolean dir = Files.isDirectory(p);
            long size = 0;
            if (!dir) {
                try { size = Files.size(p); } catch (IOException ignored) {}
            }
            entries.add(new DirListingPayload.Entry(p.getFileName().toString(), dir, size));
        }

        String relForClient = root.relativize(target).toString().replace('\\', '/');
        ServerPlayNetworking.send(player, new DirListingPayload(relForClient, entries));
    }
}
