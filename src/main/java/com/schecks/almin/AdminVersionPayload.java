package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -&gt; optional admin extension: the admin-suite build this server expects. */
public record AdminVersionPayload(String version) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AdminVersionPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:admin_version"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminVersionPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), AdminVersionPayload::version,
            AdminVersionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<AdminVersionPayload> type() {
        return TYPE;
    }
}
