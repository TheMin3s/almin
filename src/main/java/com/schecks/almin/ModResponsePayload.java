package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; server: what the player chose for the offered mods.
 *
 * <p>{@code accepted} means they pressed Approve and the downloads were started;
 * {@code installed} is how many actually landed on disk, so the server log can
 * record "approved but 2 of 3 failed" rather than guessing.
 *
 * <p>This is self-reported. A modified client can send {@code accepted=true}
 * without installing anything, so the kick-on-deny setting is a house rule the
 * honest client enforces, not a security control.
 */
public record ModResponsePayload(boolean accepted, int installed) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ModResponsePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:mod_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ModResponsePayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, ModResponsePayload::accepted,
            ByteBufCodecs.VAR_INT, ModResponsePayload::installed,
            ModResponsePayload::new
        );

    @Override
    public CustomPacketPayload.Type<ModResponsePayload> type() {
        return TYPE;
    }
}
