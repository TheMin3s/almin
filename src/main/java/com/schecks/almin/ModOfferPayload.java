package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server -&gt; client: the mods this server suggests, sent once on join.
 *
 * <p>This is an offer, not an instruction. The client shows the list and
 * downloads nothing without the player pressing Approve. {@code denyDisconnects}
 * is included so the client can label its Deny button honestly — a player should
 * know before clicking whether declining will drop them from the server.
 */
public record ModOfferPayload(List<Offer> mods, boolean denyDisconnects) implements CustomPacketPayload {

    /** One offered mod. {@code sha256} may be empty, meaning "unpinned". */
    public record Offer(String modId, String name, String version, String url,
                        String sha256, boolean required) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Offer> CODEC =
            StreamCodec.composite(
                ByteBufCodecs.stringUtf8(128), Offer::modId,
                ByteBufCodecs.stringUtf8(128), Offer::name,
                ByteBufCodecs.stringUtf8(64), Offer::version,
                ByteBufCodecs.stringUtf8(1024), Offer::url,
                ByteBufCodecs.stringUtf8(128), Offer::sha256,
                ByteBufCodecs.BOOL, Offer::required,
                Offer::new
            );
    }

    public static final CustomPacketPayload.Type<ModOfferPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:mod_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ModOfferPayload> CODEC =
        StreamCodec.composite(
            Offer.CODEC.apply(ByteBufCodecs.list(ModOffers.MAX_OFFERS)), ModOfferPayload::mods,
            ByteBufCodecs.BOOL, ModOfferPayload::denyDisconnects,
            ModOfferPayload::new
        );

    @Override
    public CustomPacketPayload.Type<ModOfferPayload> type() {
        return TYPE;
    }
}
