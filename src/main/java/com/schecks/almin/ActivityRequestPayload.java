package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: send me the activity log, or clear it.
 *
 * <p>{@code action} is {@code "get"}, {@code "clear"}, or one of the
 * admin-tracking forms below. The handler re-checks {@code TrustedOps}, so a
 * crafted packet from an ordinary player neither reads the log, erases it, nor
 * changes who is in it.
 */
public record ActivityRequestPayload(String action) implements CustomPacketPayload {

    /** Long enough for the longest form, {@code "admins temp clear"}. */
    public static final int MAX_ACTION = 32;

    public static final ActivityRequestPayload GET = new ActivityRequestPayload("get");
    public static final ActivityRequestPayload CLEAR = new ActivityRequestPayload("clear");

    /** Record admins, or stop, as a saved setting. */
    public static ActivityRequestPayload admins(boolean on) {
        return new ActivityRequestPayload("admins " + (on ? "on" : "off"));
    }

    /** The same, until the server restarts; {@code null} follows the setting again. */
    public static ActivityRequestPayload adminsTemp(Boolean on) {
        return new ActivityRequestPayload("admins temp "
            + (on == null ? "clear" : on ? "on" : "off"));
    }

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
