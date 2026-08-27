package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server -&gt; client: the rendered contents of the {@code /almin} dashboard.
 *
 * The server does the measuring and the formatting, so the same rows can be
 * drawn on the screen by a modded client or printed to chat for a vanilla one.
 * {@code trusted} tells the screen whether to offer the {@code /almin op}
 * destinations — the commands behind those buttons re-check the caller
 * server-side regardless, so this only decides what is worth drawing.
 */
public record DashboardPayload(List<Row> rows, boolean trusted) implements CustomPacketPayload {

    /** Row kinds. {@link #HEADER} starts a section; {@link #NOTE} is a full-width line. */
    public static final int HEADER = 0;
    public static final int METRIC = 1;
    public static final int NOTE   = 2;

    /**
     * One line of the dashboard. {@code value} is empty for headers and notes.
     * {@code accent} is an ARGB colour for the value, or 0 for the default.
     */
    public record Row(int kind, String label, String value, int accent) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Row::kind,
                ByteBufCodecs.stringUtf8(128), Row::label,
                ByteBufCodecs.stringUtf8(128), Row::value,
                ByteBufCodecs.INT, Row::accent,
                Row::new
            );

        public static Row header(String label)               { return new Row(HEADER, label, "", 0); }
        public static Row metric(String label, String value)  { return new Row(METRIC, label, value, 0); }
        public static Row metric(String label, String value, int accent) {
            return new Row(METRIC, label, value, accent);
        }
        public static Row note(String text)                   { return new Row(NOTE, text, "", 0); }
    }

    public static final int MAX_ROWS = 512;

    public static final CustomPacketPayload.Type<DashboardPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:dashboard"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DashboardPayload> CODEC =
        StreamCodec.composite(
            Row.CODEC.apply(ByteBufCodecs.list(MAX_ROWS)), DashboardPayload::rows,
            ByteBufCodecs.BOOL, DashboardPayload::trusted,
            DashboardPayload::new
        );

    @Override
    public CustomPacketPayload.Type<DashboardPayload> type() {
        return TYPE;
    }
}
