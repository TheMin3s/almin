package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: edited file contents from the nano editor, to be written
 * back to disk. The server handler re-checks TrustedOps and confines the path
 * to the server directory — a modded client can forge this packet, so neither
 * the sender's trust nor the path is taken on faith.
 */
public record NanoSavePayload(String path, String content) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NanoSavePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:nano_save"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NanoSavePayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1024), NanoSavePayload::path,
            ByteBufCodecs.stringUtf8(NanoOpenPayload.MAX_CHARS), NanoSavePayload::content,
            NanoSavePayload::new
        );

    @Override
    public CustomPacketPayload.Type<NanoSavePayload> type() {
        return TYPE;
    }
}
