package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: send me the activity log, or clear it.
 *
 * <p>{@code action} is {@code "get"} or {@code "clear"}. The handler re-checks
 * {@code TrustedOps}, so a crafted packet from an ordinary player neither reads
 * the log nor erases it.
 */
public record ActivityRequestPayload(String action) implements CustomPacketPayload {

    public static final int MAX_ACTION = 16;

    public static final ActivityRequestPayload GET = new ActivityRequestPayload("get");
    public static final ActivityRequestPayload CLEAR = new ActivityRequestPayload("clear");

    public static final CustomPacketPayload.Type<ActivityRequestPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:activity_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActivityRequestPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_ACTION), ActivityRequestPayload::action,
            ActivityRequestPayload::new
        );

    @Override
    public CustomPacketPayload.Type<ActivityRequestPayload> type() {
        return TYPE;
    }
}
