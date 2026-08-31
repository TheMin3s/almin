package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Packet types available only when the optional Almin Admin Suite is installed. */
public final class AdminPayloads {
    private static boolean registered = false;

    /** Worst case for one nano editor packet. */
    private static final int NANO_MAX_BYTES = NanoOpenPayload.MAX_CHARS * 4 + 8192;

    /** Worst case for one console batch: lines * (chars * utf-8 + length) + slack. */
    private static final int CONSOLE_MAX_BYTES =
        ConsoleLinesPayload.MAX_LINES_PER_BATCH
            * (ConsoleLinesPayload.MAX_LINE_CHARS * 4 + 8)
            + 8192;

    private AdminPayloads() {}

    /** Declares the admin extension's payload types, once per physical side. */
    public static synchronized void registerTypes() {
        if (registered) return;
        registered = true;

        // ---- server -> admin client ----
        PayloadTypeRegistry.clientboundPlay().register(
            AdminVersionPayload.TYPE, AdminVersionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            DirListingPayload.TYPE, DirListingPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            ConsoleOpenPayload.TYPE, ConsoleOpenPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            WebAdminPayload.TYPE, WebAdminPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            NanoOpenPayload.TYPE, NanoOpenPayload.CODEC, NANO_MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            ConsoleLinesPayload.TYPE, ConsoleLinesPayload.CODEC, CONSOLE_MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            ActivityPayload.TYPE, ActivityPayload.CODEC, ActivityPayload.MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            PanelPayload.TYPE, PanelPayload.CODEC, PanelPayload.MAX_BYTES);

        // ---- admin client -> server ----
        PayloadTypeRegistry.serverboundPlay().register(
            DirRequestPayload.TYPE, DirRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            ConsoleSubscribePayload.TYPE, ConsoleSubscribePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            WebAdminRequestPayload.TYPE, WebAdminRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            WebPasswordPayload.TYPE, WebPasswordPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            WebControlPayload.TYPE, WebControlPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            ActivityRequestPayload.TYPE, ActivityRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().registerLarge(
            NanoSavePayload.TYPE, NanoSavePayload.CODEC, NANO_MAX_BYTES);
        PayloadTypeRegistry.serverboundPlay().registerLarge(
            FileUploadPayload.TYPE, FileUploadPayload.CODEC,
            FileUploadPayload.MAX_BYTES + 8192);
    }
}
