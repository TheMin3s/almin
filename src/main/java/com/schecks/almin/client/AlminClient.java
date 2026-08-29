package com.schecks.almin.client;

import com.schecks.almin.AlminPayloads;
import com.schecks.almin.ConsoleLinesPayload;
import com.schecks.almin.ConsoleOpenPayload;
import com.schecks.almin.DashboardPayload;
import com.schecks.almin.DirListingPayload;
import com.schecks.almin.FileTransferPayload;
import com.schecks.almin.ModFilePayload;
import com.schecks.almin.ModOfferPayload;
import com.schecks.almin.NanoOpenPayload;
import com.schecks.almin.ServerVersionPayload;
import com.schecks.almin.ActivityPayload;
import com.schecks.almin.PanelPayload;
import com.schecks.almin.WebAdminPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client-side half of Almin — the screens the server's admin tools open:
 *  - shared/offered file transfers, with a download confirmation;
 *  - the nano file editor and the directory browser;
 *  - the live console viewer;
 *  - the /almin dashboard;
 *  - the self-updater, driven by the server's version handshake.
 *
 * This class (and everything in the client package) is only loaded on a
 * physical client — the dedicated server never touches it.
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

        // Receive a nano editing session; open the editor on the main thread.
        ClientPlayNetworking.registerGlobalReceiver(NanoOpenPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreenAndShow(
                new NanoEditorScreen(payload.path(), payload.content()))));

        // Receive a directory listing; open/refresh the file browser.
        ClientPlayNetworking.registerGlobalReceiver(DirListingPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreenAndShow(
                new DirBrowserScreen(payload.path(), payload.entries()))));

        // Receive the server's suggested mods and ask the player about them.
        ClientPlayNetworking.registerGlobalReceiver(ModOfferPayload.TYPE, (payload, context) ->
            context.client().execute(() ->
                ModOfferScreen.offer(payload.mods(), payload.denyDisconnects())));

        // Receive a server-hosted mod jar the player asked for. Handed straight
        // to the waiting install thread — no main-thread hop, no screen change.
        ClientPlayNetworking.registerGlobalReceiver(ModFilePayload.TYPE, (payload, context) ->
            ClientModInstaller.deliver(payload));

        // Web panel status for the in-game Web tab.
        ClientPlayNetworking.registerGlobalReceiver(WebAdminPayload.TYPE, (payload, context) ->
            context.client().execute(() -> WebPanelScreen.show(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ActivityPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ActivityScreen.show(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PanelPayload.TYPE, (payload, context) ->
            context.client().execute(() -> PanelScreen.show(payload)));

        // Receive the server's Almin version; self-update if we're behind.
        ClientPlayNetworking.registerGlobalReceiver(ServerVersionPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientUpdater.onServerVersion(payload.version())));

        // Console viewer: server says "open it", and streams batches of lines.
        ClientPlayNetworking.registerGlobalReceiver(ConsoleOpenPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreenAndShow(new ConsoleScreen())));
        ClientPlayNetworking.registerGlobalReceiver(ConsoleLinesPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ConsoleScreen.appendLines(payload.lines())));

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
