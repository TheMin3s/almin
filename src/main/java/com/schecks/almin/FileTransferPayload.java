package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client transfer of an offered file's bytes.
 *
 * Registered as a "large" payload so it can exceed the normal packet size
 * limit. Capped at {@link #MAX_BYTES}; the server refuses to offer or send
 * anything bigger, and the codec rejects oversized arrays on decode.
 */
public record FileTransferPayload(String filename, byte[] data) implements CustomPacketPayload {

    public static final int MAX_BYTES = 8 * 1024 * 1024;   // 8 MB

    public static final CustomPacketPayload.Type<FileTransferPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:file_transfer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FileTransferPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(256), FileTransferPayload::filename,
            ByteBufCodecs.byteArray(MAX_BYTES), FileTransferPayload::data,
            FileTransferPayload::new
        );

    @Override
    public CustomPacketPayload.Type<FileTransferPayload> type() {
        return TYPE;
    }
}
