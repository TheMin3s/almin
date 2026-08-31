package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Tells an authenticated administrator's base client which official admin
 * extension to install. The server supplies only a version; the client keeps
 * the repository and asset classifier hardcoded.
 */
public record AdminInstallPayload(String version) implements CustomPacketPayload {
    public static final Type<AdminInstallPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("almin", "admin_install"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminInstallPayload> CODEC =
        StreamCodec.composite(ByteBufCodecs.stringUtf8(64), AdminInstallPayload::version,
            AdminInstallPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
