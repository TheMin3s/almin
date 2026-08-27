package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: the Almin version the server runs, sent once when a
 * modded client joins. The client uses it to self-sync (see ClientUpdater).
 *
 * It carries only a version <em>number</em> — never a download URL or repo.
 * The client downloads strictly from its own hardcoded official repo, so a
 * hostile server can at most trigger a fetch of a real Almin release.
 */
public record ServerVersionPayload(String version) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerVersionPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:server_version"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerVersionPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), ServerVersionPayload::version,
            ServerVersionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<ServerVersionPayload> type() {
        return TYPE;
    }
}
