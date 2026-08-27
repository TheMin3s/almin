package com.schecks.almin.client;

import com.schecks.almin.ConsoleLinesPayload;
import com.schecks.almin.ConsoleOpenPayload;
import com.schecks.almin.DashboardPayload;
import com.schecks.almin.DirListingPayload;
import com.schecks.almin.FileTransferPayload;
import com.schecks.almin.NanoOpenPayload;
import com.schecks.almin.ServerVersionPayload;
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
        // Receive shared-file transfers; hop to the main thread to show the
        // confirmation screen.
        ClientPlayNetworking.registerGlobalReceiver(FileTransferPayload.TYPE, (payload, context) ->
            context.client().execute(() -> FileDownloadHandler.handle(payload)));

        // Receive a dashboard snapshot; open (or replace) the dashboard screen.
        ClientPlayNetworking.registerGlobalReceiver(DashboardPayload.TYPE, (payload, context) ->
            context.client().execute(() -> DashboardScreen.show(payload.rows(), payload.trusted())));

        // Receive a nano editing session; open the editor on the main thread.
        ClientPlayNetworking.registerGlobalReceiver(NanoOpenPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreen(
                new NanoEditorScreen(payload.path(), payload.content()))));

        // Receive a directory listing; open/refresh the file browser.
        ClientPlayNetworking.registerGlobalReceiver(DirListingPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreen(
                new DirBrowserScreen(payload.path(), payload.entries()))));

        // Receive the server's Almin version; self-update if we're behind.
        ClientPlayNetworking.registerGlobalReceiver(ServerVersionPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientUpdater.onServerVersion(payload.version())));

        // Console viewer: server says "open it", and streams batches of lines.
        ClientPlayNetworking.registerGlobalReceiver(ConsoleOpenPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreen(new ConsoleScreen())));
        ClientPlayNetworking.registerGlobalReceiver(ConsoleLinesPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ConsoleScreen.appendLines(payload.lines())));

        // Drop cached state when we leave a server, so the next join is
        // evaluated fresh by ClientUpdater.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            ClientUpdater.reset());
    }
}
