package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: the bytes of a server-hosted mod jar the player approved.
 *
 * <p>Registered as a "large" payload — mod jars routinely exceed the normal
 * packet limit. {@code data} is empty when the server could not serve the file,
 * with {@code error} saying why, so a client is never left waiting on a reply
 * that will not come.
 */
public record ModFilePayload(String modId, String filename, String error, byte[] data)
        implements CustomPacketPayload {

    /** Matches {@link ModOffers#MAX_FILE_BYTES}. */
    public static final int MAX_BYTES = 32 * 1024 * 1024;

    public static final CustomPacketPayload.Type<ModFilePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:mod_file"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ModFilePayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(128), ModFilePayload::modId,
            ByteBufCodecs.stringUtf8(256), ModFilePayload::filename,
            ByteBufCodecs.stringUtf8(256), ModFilePayload::error,
            ByteBufCodecs.byteArray(MAX_BYTES), ModFilePayload::data,
            ModFilePayload::new
        );

    public boolean ok() {
        return error.isEmpty() && data.length > 0;
    }

    @Override
    public CustomPacketPayload.Type<ModFilePayload> type() {
        return TYPE;
    }
}
