package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Receiving what a client says it is running.
 *
 * <p>One packet, at join, and nothing is asked for: a server that polled its
 * players for their mod lists would be doing something else.
 */
public final class ClientProfileNet {
    /** Restricted mods are worth the console, since somebody has to act on them. */
    private static final org.slf4j.Logger CONSOLE = org.slf4j.LoggerFactory.getLogger("almin");

    private ClientProfileNet() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ClientProfilePayload.TYPE,
            (payload, context) -> {
                ServerPlayer player = context.player();
                context.server().execute(() -> accept(player, payload));
            });
    }

    private static void accept(ServerPlayer player, ClientProfilePayload said) {
        if (!AlminConfig.get().clientReport) return;
        String name = player.getGameProfile().name();
        List<String> added = ClientProfiles.record(player.getUUID(), name, said);
        if (!added.isEmpty()) {
            AlminLog.info("[almin] {} added {} client mod(s) since last join: {}",
                name, added.size(), String.join(", ", added.subList(0, Math.min(8, added.size()))));
        }
        enforce(player, name);
    }

    /**
     * What happens when a player turns up with something on the restricted list.
     *
     * <p>Logged always, and disconnected only if asked for: the list is
     * self-reported, so a kick is a house rule enforced on the honest. Saying
     * which mod, in the message, is the difference between a rule and a wall.
     */
    private static void enforce(ServerPlayer player, String name) {
        ClientProfiles.Profile profile = ClientProfiles.of(player.getUUID());
        List<String> hits = ClientProfiles.restricted(profile);
        if (hits.isEmpty()) return;

        String list = String.join(", ", hits);
        AlminLog.info("[almin] {} is running restricted mod(s): {}", name, list);
        CONSOLE.info("[almin] {} is running restricted mod(s): {}", name, list);
        if (!AlminConfig.get().modsRestrictedKick) {
            player.sendSystemMessage(Component.literal(
                "This server asks players not to run: " + list));
            return;
        }
        player.connection.disconnect(Component.literal(
            "This server does not allow these mods:\n\n" + list
            + "\n\nRemove them and rejoin."));
    }
}
