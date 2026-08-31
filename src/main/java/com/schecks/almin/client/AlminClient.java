package com.schecks.almin.client;

import com.schecks.almin.AlminPayloads;
import com.schecks.almin.AdminInstallPayload;
import com.schecks.almin.DashboardPayload;
import com.schecks.almin.FileTransferPayload;
import com.schecks.almin.ModFilePayload;
import com.schecks.almin.ModOfferPayload;
import com.schecks.almin.ServerVersionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The lightweight, player-facing Almin client:
 *  - suggested and server-hosted mod installation;
 *  - client mod/runtime reporting;
 *  - shared-file downloads and the ordinary dashboard;
 *  - the self-updater, driven by the server's version handshake.
 *
 * Server administration screens are deliberately registered by the separate
 * {@link AlminAdminClient} entrypoint, shipped in the optional admin jar.
 */
public class AlminClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // The client jar has no main entrypoint, so it declares the packet
        // types itself. Idempotent — a universal jar runs both entrypoints.
        AlminPayloads.registerTypes();
        // Keeps itself current without needing to join a server first; the
        // download applies at the next launch, since a jar cannot be swapped
        // under a running game.
        ClientUpdater.startBackgroundChecks();

        // Receive shared-file transfers; hop to the main thread to show the
        // confirmation screen.
        ClientPlayNetworking.registerGlobalReceiver(FileTransferPayload.TYPE, (payload, context) ->
            context.client().execute(() -> FileDownloadHandler.handle(payload)));

        // Receive a dashboard snapshot; open (or replace) the dashboard screen.
        ClientPlayNetworking.registerGlobalReceiver(DashboardPayload.TYPE, (payload, context) ->
            context.client().execute(() -> DashboardScreen.show(payload.rows(), payload.tiles(), payload.trusted())));

        // Receive the server's suggested mods and ask the player about them.
        ClientPlayNetworking.registerGlobalReceiver(ModOfferPayload.TYPE, (payload, context) ->
            context.client().execute(() ->
                ModOfferScreen.offer(payload.mods(), payload.denyDisconnects())));

        // Receive a server-hosted mod jar the player asked for. Handed straight
        // to the waiting install thread — no main-thread hop, no screen change.
        ClientPlayNetworking.registerGlobalReceiver(ModFilePayload.TYPE, (payload, context) ->
            ClientModInstaller.deliver(payload));

        // Receive the server's Almin version; self-update if we're behind.
        ClientPlayNetworking.registerGlobalReceiver(ServerVersionPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientUpdater.onServerVersion(payload.version())));
        // A server may offer the optional extension only after it has
        // authenticated this player as an administrator. The client still
        // downloads solely from Almin's hardcoded official repository.
        ClientPlayNetworking.registerGlobalReceiver(AdminInstallPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientUpdater.onAdminInstall(payload.version())));

        // Drop cached state when we leave a server, so the next join is
        // evaluated fresh by ClientUpdater.
        // Told once, at join, and only to a server that asked for the mod.
        // Nothing polls for this and nothing asks again.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            ClientProfileReport.send());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            ClientUpdater.reset());
    }
}
