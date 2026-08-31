package com.schecks.almin.client;

import com.schecks.almin.ActivityPayload;
import com.schecks.almin.AdminPayloads;
import com.schecks.almin.AdminVersionPayload;
import com.schecks.almin.ConsoleLinesPayload;
import com.schecks.almin.ConsoleOpenPayload;
import com.schecks.almin.DirListingPayload;
import com.schecks.almin.NanoOpenPayload;
import com.schecks.almin.PanelPayload;
import com.schecks.almin.WebAdminPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Optional client entrypoint containing Almin's server-administration suite. */
public final class AlminAdminClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AdminPayloads.registerTypes();

        ClientPlayNetworking.registerGlobalReceiver(NanoOpenPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreenAndShow(
                new NanoEditorScreen(payload.path(), payload.content()))));
        ClientPlayNetworking.registerGlobalReceiver(DirListingPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreenAndShow(
                new DirBrowserScreen(payload.path(), payload.entries()))));
        ClientPlayNetworking.registerGlobalReceiver(WebAdminPayload.TYPE, (payload, context) ->
            context.client().execute(() -> WebPanelScreen.show(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ActivityPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ActivityScreen.show(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PanelPayload.TYPE, (payload, context) ->
            context.client().execute(() -> PanelScreen.show(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ConsoleOpenPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreenAndShow(new ConsoleScreen())));
        ClientPlayNetworking.registerGlobalReceiver(ConsoleLinesPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ConsoleScreen.appendLines(payload.lines())));

        // Kept separate from the base handshake: an admin-only release can
        // update this jar without making ordinary players replace theirs.
        ClientPlayNetworking.registerGlobalReceiver(AdminVersionPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientUpdater.onAdminServerVersion(payload.version())));
    }
}
