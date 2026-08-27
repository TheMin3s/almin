package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: a file uploaded from the player's computer via the dir
 * browser's drag-and-drop.
 *
 * Registered as a large payload — Fabric transparently splits it into 32 KB
 * fragments on the wire and reassembles, so it carries well past the normal
 * packet-size limit. The server handler (UploadNet) re-checks TrustedOps and
 * confines the destination, since a modded client can forge this packet.
 */
public record FileUploadPayload(String destPath, byte[] data) implements CustomPacketPayload {

    public static final int MAX_BYTES = 50 * 1024 * 1024;   // 50 MB

    public static final CustomPacketPayload.Type<FileUploadPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:file_upload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FileUploadPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1024), FileUploadPayload::destPath,
            ByteBufCodecs.byteArray(MAX_BYTES), FileUploadPayload::data,
            FileUploadPayload::new
        );

    @Override
    public CustomPacketPayload.Type<FileUploadPayload> type() {
        return TYPE;
    }
}
