package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: what the web panel is currently doing, for the in-game
 * Web tab.
 *
 * <p>Carries no password and no hash — only whether one has been set. There is
 * nothing here a player could not already read off {@code /almin op web}.
 */
public record WebAdminPayload(boolean running, String bind, int port, boolean passwordSet,
                              boolean publicMetrics, boolean requireSecure, String url)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WebAdminPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:web_admin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WebAdminPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, WebAdminPayload::running,
            ByteBufCodecs.stringUtf8(64), WebAdminPayload::bind,
            ByteBufCodecs.VAR_INT, WebAdminPayload::port,
            ByteBufCodecs.BOOL, WebAdminPayload::passwordSet,
            ByteBufCodecs.BOOL, WebAdminPayload::publicMetrics,
            ByteBufCodecs.BOOL, WebAdminPayload::requireSecure,
            ByteBufCodecs.stringUtf8(256), WebAdminPayload::url,
            WebAdminPayload::new
        );

    @Override
    public CustomPacketPayload.Type<WebAdminPayload> type() {
        return TYPE;
    }
}
