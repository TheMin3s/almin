package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -&gt; server: "tell me the web panel's current state". Carries nothing. */
public record WebAdminRequestPayload() implements CustomPacketPayload {

    public static final WebAdminRequestPayload INSTANCE = new WebAdminRequestPayload();

    public static final CustomPacketPayload.Type<WebAdminRequestPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:web_admin_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WebAdminRequestPayload> CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<WebAdminRequestPayload> type() {
        return TYPE;
    }
}
