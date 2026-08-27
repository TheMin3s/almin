package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: "send me the jar for this offered mod".
 *
 * <p>Carries only a mod id, never a path. The server looks that id up in its own
 * offer list and serves the file that list names, so a client cannot ask for an
 * arbitrary file on disk.
 */
public record ModFileRequestPayload(String modId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ModFileRequestPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:mod_file_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ModFileRequestPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(128), ModFileRequestPayload::modId,
            ModFileRequestPayload::new
        );

    @Override
    public CustomPacketPayload.Type<ModFileRequestPayload> type() {
        return TYPE;
    }
}
