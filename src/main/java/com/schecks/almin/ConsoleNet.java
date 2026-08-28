package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Networking for the live console viewer:
 *  - ConsoleOpenPayload      (S2C): tells a client to open the viewer screen.
 *  - ConsoleSubscribePayload (C2S): client subscribes/unsubscribes from the
 *    live feed. The handler re-checks TrustedOps every time.
 *  - ConsoleLinesPayload     (S2C, large): batches of console lines, both the
 *    initial history and the live tail.
 */
public final class ConsoleNet {
    private ConsoleNet() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ConsoleSubscribePayload.TYPE, (payload, context) -> {
            var server = context.server();
            var player = context.player();
            server.execute(() -> {
                if (!TrustedOps.isTrusted(player.getUUID())) return;
                ConsoleTap tap = ConsoleTap.get();
                if (tap == null) return;
                if (payload.subscribe()) tap.subscribe(player);
                else tap.unsubscribe(player.getUUID());
            });
        });

        // Clean up subscriptions when a player drops out.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ConsoleTap tap = ConsoleTap.get();
            if (tap != null) tap.unsubscribe(handler.getPlayer().getUUID());
        });
    }
}
