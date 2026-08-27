package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: opens the in-game nano editor with a file's current
 * contents. Sent by /almin op nano &lt;path&gt; when the caller is on a modded
 * client; vanilla clients fall back to the Writable Book editor.
 */
public record NanoOpenPayload(String path, String content) implements CustomPacketPayload {

    /** Editor text cap — also the server's file-size gate for the editor path. */
    public static final int MAX_CHARS = 128 * 1024;

    public static final CustomPacketPayload.Type<NanoOpenPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:nano_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NanoOpenPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1024), NanoOpenPayload::path,
            ByteBufCodecs.stringUtf8(MAX_CHARS), NanoOpenPayload::content,
            NanoOpenPayload::new
        );

    @Override
    public CustomPacketPayload.Type<NanoOpenPayload> type() {
        return TYPE;
    }
}
