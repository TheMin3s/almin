package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: set the web panel's admin password.
 *
 * <p>This exists so the password does not have to be typed into chat, where it
 * is visible to anyone looking at the screen and lands in the server log.
 * It travels on the game connection instead, and the server hashes it on
 * arrival — the plaintext is never stored or logged.
 *
 * <p>The handler re-checks {@code TrustedOps} on arrival, so a crafted packet
 * from an ordinary player achieves nothing.
 */
public record WebPasswordPayload(String password) implements CustomPacketPayload {

    /** Long enough for a real passphrase, short enough to bound the packet. */
    public static final int MAX_LENGTH = 256;

    public static final CustomPacketPayload.Type<WebPasswordPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:web_password"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WebPasswordPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_LENGTH), WebPasswordPayload::password,
            WebPasswordPayload::new
        );

    @Override
    public CustomPacketPayload.Type<WebPasswordPayload> type() {
        return TYPE;
    }
}
