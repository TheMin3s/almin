package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server -&gt; client: a batch of server-console lines streamed to subscribed
 * clients. The first batch after subscribe is the recent history (server's
 * ring buffer); subsequent batches are new lines as they appear in the log.
 */
public record ConsoleLinesPayload(List<String> lines) implements CustomPacketPayload {

    /** Max characters per line; the server truncates longer lines with "…". */
    public static final int MAX_LINE_CHARS = 1024;
    /** Max lines per batch; matches the server's ring-buffer size. */
    public static final int MAX_LINES_PER_BATCH = 500;

    public static final CustomPacketPayload.Type<ConsoleLinesPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:console_lines"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleLinesPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_LINE_CHARS).apply(ByteBufCodecs.list(MAX_LINES_PER_BATCH)),
            ConsoleLinesPayload::lines,
            ConsoleLinesPayload::new
        );

    @Override
    public CustomPacketPayload.Type<ConsoleLinesPayload> type() {
        return TYPE;
    }
}
