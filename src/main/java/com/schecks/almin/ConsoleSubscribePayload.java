package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: subscribe or unsubscribe to the live console feed. Sent
 * by the console viewer screen when it opens (true) and closes (false). The
 * server re-checks TrustedOps on each subscribe.
 */
public record ConsoleSubscribePayload(boolean subscribe) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ConsoleSubscribePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:console_subscribe"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleSubscribePayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, ConsoleSubscribePayload::subscribe,
            ConsoleSubscribePayload::new
        );

    @Override
    public CustomPacketPayload.Type<ConsoleSubscribePayload> type() {
        return TYPE;
    }
}
