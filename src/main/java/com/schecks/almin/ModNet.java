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
import java.util.List;

/**
 * Networking for the advertised-mods feature:
 *  - ModOfferPayload    (S2C): the list of suggested mods, sent on join.
 *  - ModResponsePayload (C2S): what the player chose.
 *
 * The response handler is the only place the {@code mods-deny-kicks} rule is
 * applied. It re-reads the config and the offer list rather than trusting
 * anything in the packet, because a client can send whatever it likes.
 */
public final class ModNet {
    private ModNet() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ModOfferPayload.TYPE, ModOfferPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModResponsePayload.TYPE, ModResponsePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            ModFileRequestPayload.TYPE, ModFileRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            ModFilePayload.TYPE, ModFilePayload.CODEC, ModFilePayload.MAX_BYTES + 8192);

        ServerPlayNetworking.registerGlobalReceiver(ModFileRequestPayload.TYPE, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            server.execute(() -> serveFile(player, payload.modId()));
        });

        ServerPlayNetworking.registerGlobalReceiver(ModResponsePayload.TYPE, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            server.execute(() -> handleResponse(player, payload));
        });
    }

    private static void handleResponse(ServerPlayer player, ModResponsePayload payload) {
        String who = player.getGameProfile().name();
        if (payload.accepted()) {
            AlminLog.info("[almin] {} approved the advertised mods ({} installed)", who, payload.installed());
            player.sendSystemMessage(Component.literal(
                    "Thanks — restart Minecraft to load the mods you just installed.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
            return;
        }

        AlminLog.info("[almin] {} declined the advertised mods", who);
        // Only a declined *required* mod is grounds for a disconnect.
        if (AlminConfig.get().modsDenyKicks && ModOffers.anyRequired()) {
            AlminLog.info("[almin] disconnecting {} — required mods declined", who);
            player.connection.disconnect(Component.literal(
                "This server requires the mods it offered.\n"
                    + "Rejoin and choose Approve, or install them manually."));
            return;
        }
        player.sendSystemMessage(Component.literal(
                "No problem — you can install them later with /almin mods.")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    /**
     * Serves a server-hosted jar to a player who asked for it.
     *
     * <p>The request carries only a mod id. That id is looked up in this
     * server's own offer list and the filename comes from there, never from the
     * client — so this cannot be turned into "read me any file". Anything not
     * currently advertised, not server-hosted, missing, or over the size cap is
     * refused with a reason rather than silently dropped.
     */
    private static void serveFile(ServerPlayer player, String modId) {
        String who = player.getGameProfile().name();
        ModOffers.AdvertisedMod offer = ModOffers.list().stream()
            .filter(m -> m.modId().equals(modId))
            .findFirst().orElse(null);

        if (offer == null || !offer.serverHosted()) {
            AlminLog.warn("[almin] {} asked for mod file '{}' which isn't advertised here", who, modId);
            reject(player, modId, "not offered by this server");
            return;
        }
        Path file = ModOffers.resolveModFile(offer.file());
        if (file == null || !Files.isRegularFile(file)) {
            AlminLog.warn("[almin] mod file for '{}' is missing from modfiles/", modId);
            reject(player, modId, "the server no longer has this file");
            return;
        }
        try {
            long size = Files.size(file);
            if (size > ModOffers.MAX_FILE_BYTES) {
                reject(player, modId, "file is larger than the " + (ModOffers.MAX_FILE_BYTES / (1024 * 1024)) + " MB limit");
                return;
            }
            byte[] data = Files.readAllBytes(file);
            AlminLog.info("[almin] sent {} the jar for {} ({} bytes)", who, modId, data.length);
            ServerPlayNetworking.send(player,
                new ModFilePayload(modId, file.getFileName().toString(), "", data));
        } catch (IOException e) {
            AlminLog.warn("[almin] could not read mod file for {}: {}", modId, e.getMessage());
            reject(player, modId, "the server could not read the file");
        }
    }

    private static void reject(ServerPlayer player, String modId, String reason) {
        ServerPlayNetworking.send(player, new ModFilePayload(modId, "", reason, new byte[0]));
    }

    /**
     * Sends the offer list to {@code player}, if there is anything to send and
     * their client can receive it. Returns true if an offer went out.
     */
    public static boolean sendOffers(ServerPlayer player) {
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.modsAdvertise) return false;
        List<ModOffers.AdvertisedMod> offers = ModOffers.list();
        if (offers.isEmpty()) return false;
        if (!ServerPlayNetworking.canSend(player, ModOfferPayload.TYPE)) return false;

        List<ModOfferPayload.Offer> wire = new ArrayList<>(offers.size());
        for (ModOffers.AdvertisedMod m : offers) {
            // Belt and braces: the store already validates both sources, but
            // this is the last point before anything reaches another machine.
            if (m.serverHosted()) {
                if (ModOffers.resolveModFile(m.file()) == null) continue;
            } else if (!ModOffers.isHttps(m.url())) {
                continue;
            }
            wire.add(new ModOfferPayload.Offer(
                m.modId(), m.name(), m.version(), m.url(), m.sha256(), m.required(), m.file()));
        }
        if (wire.isEmpty()) return false;
        ServerPlayNetworking.send(player, new ModOfferPayload(wire, cfg.modsDenyKicks));
        return true;
    }
}
