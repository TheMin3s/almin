package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: request a directory listing (path relative to the server
 * directory). The server handler re-checks TrustedOps and confines the path to
 * the server directory before replying with a DirListingPayload.
 */
public record DirRequestPayload(String path) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DirRequestPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:dir_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DirRequestPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1024), DirRequestPayload::path,
            DirRequestPayload::new
        );

    @Override
    public CustomPacketPayload.Type<DirRequestPayload> type() {
        return TYPE;
    }
}
