package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -&gt; client: a page of the activity log for the in-game Activity tab.
 *
 * <p>Only ever sent to a trusted op — {@link ActivityNet} re-checks on the way
 * out — because this is a record of named people and who may read it is the
 * whole point of keeping it.
 *
 * <p>Hand-written codec: a list of seven-field rows is past what
 * {@code StreamCodec.composite} builds.
 */
public record ActivityPayload(List<ActivityLog.Entry> entries, int total,
                              int retentionMinutes, boolean enabled)
        implements CustomPacketPayload {

    /** Rows in one packet. The screen scrolls; it does not need the whole log. */
    public static final int MAX_ROWS = 400;

    private static final int MAX_NAME = 64;
    private static final int MAX_UUID = 48;
    private static final int MAX_ACTION = 24;
    private static final int MAX_DETAIL = 200;
    private static final int MAX_WHERE = 80;

    /** Worst case for one packet, with slack for the framing. */
    public static final int MAX_BYTES =
        MAX_ROWS * ((MAX_NAME + MAX_UUID + MAX_ACTION + MAX_DETAIL + MAX_WHERE) * 4 + 40) + 8192;

    public static final CustomPacketPayload.Type<ActivityPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:activity"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActivityPayload> CODEC =
        StreamCodec.of(ActivityPayload::write, ActivityPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, ActivityPayload p) {
        List<ActivityLog.Entry> rows = p.entries;
        int n = Math.min(rows.size(), MAX_ROWS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            ActivityLog.Entry e = rows.get(i);
            buf.writeLong(e.at());
            buf.writeUtf(clip(e.player(), MAX_NAME), MAX_NAME);
            buf.writeUtf(clip(e.uuid(), MAX_UUID), MAX_UUID);
            buf.writeUtf(clip(e.action(), MAX_ACTION), MAX_ACTION);
            buf.writeUtf(clip(e.detail(), MAX_DETAIL), MAX_DETAIL);
            buf.writeUtf(clip(e.dim(), MAX_WHERE), MAX_WHERE);
            buf.writeVarInt(e.x());
            buf.writeVarInt(e.y());
            buf.writeVarInt(e.z());
            buf.writeVarInt(Math.max(1, e.count()));
        }
        buf.writeVarInt(p.total);
        buf.writeVarInt(p.retentionMinutes);
        buf.writeBoolean(p.enabled);
    }

    private static ActivityPayload read(RegistryFriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<ActivityLog.Entry> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new ActivityLog.Entry(
                buf.readLong(),
                buf.readUtf(MAX_NAME),
                buf.readUtf(MAX_UUID),
                buf.readUtf(MAX_ACTION),
                buf.readUtf(MAX_DETAIL),
                buf.readUtf(MAX_WHERE),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt()));
        }
        return new ActivityPayload(rows, buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    /** Truncates rather than throwing: a long line is a bad row, not a dead packet. */
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public CustomPacketPayload.Type<ActivityPayload> type() {
        return TYPE;
    }
}
