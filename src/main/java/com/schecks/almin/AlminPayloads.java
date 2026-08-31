package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Player-facing packet types carried by the base Almin client.
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
 * <p>Admin-only packet types live in {@link AdminPayloads}. Keeping the two
 * registries separate is what lets an ordinary player's jar advertise only
 * the dashboard, downloads, mod offers, profile reporting and base updater.
 */
public final class AlminPayloads {
    private static boolean registered = false;

    private AlminPayloads() {}

    /**
     * Declares base-client payload types. Safe to call from both entrypoints;
     * the second call is a no-op.
     */
    public static synchronized void registerTypes() {
        if (registered) return;
        registered = true;

        // ---- server -> client ----
        PayloadTypeRegistry.clientboundPlay().register(
            ServerVersionPayload.TYPE, ServerVersionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            AdminInstallPayload.TYPE, AdminInstallPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            DashboardPayload.TYPE, DashboardPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            ModOfferPayload.TYPE, ModOfferPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            FileTransferPayload.TYPE, FileTransferPayload.CODEC,
            FileTransferPayload.MAX_BYTES + 4096);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            ModFilePayload.TYPE, ModFilePayload.CODEC, ModFilePayload.MAX_BYTES + 8192);

        // ---- client -> server ----
        PayloadTypeRegistry.serverboundPlay().register(
            ModResponsePayload.TYPE, ModResponsePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            ModFileRequestPayload.TYPE, ModFileRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().registerLarge(
            ClientProfilePayload.TYPE, ClientProfilePayload.CODEC,
            ClientProfilePayload.MAX_BYTES);
    }
}
