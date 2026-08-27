package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: marker that tells a modded client to open the live
 * console viewer. Sent by {@code /almin op console}. Carries no fields — the
 * client opens the screen, then subscribes via {@link ConsoleSubscribePayload}.
 */
public record ConsoleOpenPayload() implements CustomPacketPayload {

    public static final ConsoleOpenPayload INSTANCE = new ConsoleOpenPayload();

    public static final CustomPacketPayload.Type<ConsoleOpenPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:console_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleOpenPayload> CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<ConsoleOpenPayload> type() {
        return TYPE;
    }
}
