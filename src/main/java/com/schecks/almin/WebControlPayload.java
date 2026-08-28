package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: run the web panel, or change one of its settings, from
 * the in-game Web tab.
 *
 * <p>{@code action} is one of {@code start}, {@code stop}, {@code restart} or
 * {@code set}. Only {@code set} uses {@code key} and {@code value}, and only
 * for a key on {@link WebAdminNet}'s allowlist — this packet cannot reach
 * arbitrary config, and in particular cannot touch {@code web-start-command},
 * which is the one setting that turns into a command on the host OS.
 *
 * <p>The handler re-checks {@code TrustedOps} on arrival, so a crafted packet
 * from an ordinary player achieves nothing.
 */
public record WebControlPayload(String action, String key, String value)
        implements CustomPacketPayload {

    public static final int MAX_ACTION = 16;
    public static final int MAX_KEY = 64;
    public static final int MAX_VALUE = 256;

    public static final CustomPacketPayload.Type<WebControlPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:web_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WebControlPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_ACTION), WebControlPayload::action,
            ByteBufCodecs.stringUtf8(MAX_KEY), WebControlPayload::key,
            ByteBufCodecs.stringUtf8(MAX_VALUE), WebControlPayload::value,
            WebControlPayload::new
        );

    /** A start/stop/restart request, which carries no setting. */
    public static WebControlPayload action(String action) {
        return new WebControlPayload(action, "", "");
    }

    /** A request to change one setting. */
    public static WebControlPayload set(String key, String value) {
        return new WebControlPayload("set", key, value);
    }

    @Override
    public CustomPacketPayload.Type<WebControlPayload> type() {
        return TYPE;
    }
}
