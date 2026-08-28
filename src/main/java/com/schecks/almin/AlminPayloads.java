package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Every custom packet type this mod uses, registered in one place.
 *
 * <h3>Why this exists</h3>
 * Registering a payload <em>type</em> is not the same as registering a
 * <em>handler</em>. The type has to be known on both sides of the connection —
 * a client cannot receive a packet, or send one, whose type it has never
 * registered.
 *
 * <p>These calls used to live next to the server-side handlers, in classes
 * reached only from the {@code main} entrypoint. That worked while the mod
 * shipped as a single universal jar, because a client ran the main entrypoint
 * too. It stopped working the moment the client jar became client-only: the
 * client registered receivers for types nothing had declared, and Fabric threw
 * at startup.
 *
 * <p>So both entrypoints call this first, and it is idempotent — a universal
 * jar runs both and must not register anything twice.
 */
public final class AlminPayloads {
    private static boolean registered = false;

    private AlminPayloads() {}

    /** Worst case for one nano editor packet. */
    private static final int NANO_MAX_BYTES = NanoOpenPayload.MAX_CHARS * 4 + 8192;

    /** Worst case for one console batch: lines * (chars * utf-8 + length) + slack. */
    private static final int CONSOLE_MAX_BYTES =
        ConsoleLinesPayload.MAX_LINES_PER_BATCH
            * (ConsoleLinesPayload.MAX_LINE_CHARS * 4 + 8)
            + 8192;

    /**
     * Declares every payload type. Safe to call from both entrypoints; the
     * second call is a no-op.
     */
    public static synchronized void registerTypes() {
        if (registered) return;
        registered = true;

        // ---- server -> client ----
        PayloadTypeRegistry.clientboundPlay().register(
            ServerVersionPayload.TYPE, ServerVersionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            DashboardPayload.TYPE, DashboardPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            DirListingPayload.TYPE, DirListingPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            ConsoleOpenPayload.TYPE, ConsoleOpenPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            ModOfferPayload.TYPE, ModOfferPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            WebAdminPayload.TYPE, WebAdminPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            FileTransferPayload.TYPE, FileTransferPayload.CODEC,
            FileTransferPayload.MAX_BYTES + 4096);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            NanoOpenPayload.TYPE, NanoOpenPayload.CODEC, NANO_MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            ConsoleLinesPayload.TYPE, ConsoleLinesPayload.CODEC, CONSOLE_MAX_BYTES);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            ModFilePayload.TYPE, ModFilePayload.CODEC, ModFilePayload.MAX_BYTES + 8192);

        // ---- client -> server ----
        PayloadTypeRegistry.serverboundPlay().register(
            DirRequestPayload.TYPE, DirRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            ConsoleSubscribePayload.TYPE, ConsoleSubscribePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            ModResponsePayload.TYPE, ModResponsePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            ModFileRequestPayload.TYPE, ModFileRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            WebAdminRequestPayload.TYPE, WebAdminRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            WebPasswordPayload.TYPE, WebPasswordPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().registerLarge(
            NanoSavePayload.TYPE, NanoSavePayload.CODEC, NANO_MAX_BYTES);
        PayloadTypeRegistry.serverboundPlay().registerLarge(
            FileUploadPayload.TYPE, FileUploadPayload.CODEC,
            FileUploadPayload.MAX_BYTES + 8192);
    }
}
