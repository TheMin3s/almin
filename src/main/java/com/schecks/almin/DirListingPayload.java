package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server -&gt; client: a directory listing for the modded-client file browser.
 * {@code path} is relative to the server directory ("" = the server root).
 */
public record DirListingPayload(String path, List<Entry> entries) implements CustomPacketPayload {

    /** One directory entry. {@code size} is meaningful only for files. */
    public record Entry(String name, boolean directory, long size) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC =
            StreamCodec.composite(
                ByteBufCodecs.stringUtf8(256), Entry::name,
                ByteBufCodecs.BOOL, Entry::directory,
                ByteBufCodecs.VAR_LONG, Entry::size,
                Entry::new
            );
    }

    public static final int MAX_ENTRIES = 500;

    public static final CustomPacketPayload.Type<DirListingPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:dir_listing"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DirListingPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1024), DirListingPayload::path,
            Entry.CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), DirListingPayload::entries,
            DirListingPayload::new
        );

    @Override
    public CustomPacketPayload.Type<DirListingPayload> type() {
        return TYPE;
    }
}
