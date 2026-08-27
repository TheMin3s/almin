package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
            // Belt and braces: the store already rejects non-https, but this is
            // the last point before a URL reaches somebody else's machine.
            if (!ModOffers.isHttps(m.url())) continue;
            wire.add(new ModOfferPayload.Offer(
                m.modId(), m.name(), m.version(), m.url(), m.sha256(), m.required()));
        }
        if (wire.isEmpty()) return false;
        ServerPlayNetworking.send(player, new ModOfferPayload(wire, cfg.modsDenyKicks));
        return true;
    }
}
