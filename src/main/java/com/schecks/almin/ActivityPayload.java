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
                              int retentionMinutes, boolean enabled,
                              List<Track> tracks, ActivityLog.AdminPolicy admins)
        implements CustomPacketPayload {

    /** One player's path, thinned to fit. See {@link PlayerTracks#everyone}. */
    public record Track(String player, List<PlayerTracks.Point> points) {}

    /** Rows in one packet. The screen scrolls; it does not need the whole log. */
    public static final int MAX_ROWS = 400;

    /** Points across all players. Enough to draw a path, small enough to send. */
    public static final int MAX_TRACK_POINTS = 1500;

    private static final int MAX_TRACKED = 64;

    private static final int MAX_NAME = 64;
    private static final int MAX_UUID = 48;
    private static final int MAX_ACTION = 24;
    private static final int MAX_DETAIL = 200;
    private static final int MAX_WHERE = 80;

    /** Worst case for one packet, with slack for the framing. */
    public static final int MAX_BYTES =
        MAX_ROWS * ((MAX_NAME + MAX_UUID + MAX_ACTION + MAX_DETAIL + MAX_WHERE) * 4 + 40)
        + MAX_TRACK_POINTS * (MAX_WHERE * 4 + 40)
        + MAX_TRACKED * (MAX_NAME * 4 + 8)
        + 8192;

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

        List<Track> tracks = p.tracks == null ? List.of() : p.tracks;
        int players = Math.min(tracks.size(), MAX_TRACKED);
        buf.writeVarInt(players);
        int left = MAX_TRACK_POINTS;
        for (int i = 0; i < players; i++) {
            Track t = tracks.get(i);
            buf.writeUtf(clip(t.player(), MAX_NAME), MAX_NAME);
            int points = Math.min(t.points().size(), Math.max(0, left));
            buf.writeVarInt(points);
            for (int j = 0; j < points; j++) {
                PlayerTracks.Point pt = t.points().get(j);
                buf.writeLong(pt.at());
                buf.writeUtf(clip(pt.dim(), MAX_WHERE), MAX_WHERE);
                buf.writeVarInt(pt.x());
                buf.writeVarInt(pt.y());
                buf.writeVarInt(pt.z());
            }
            left -= points;
        }

        ActivityLog.AdminPolicy admins = p.admins == null
            ? new ActivityLog.AdminPolicy(false, false, false) : p.admins;
        buf.writeBoolean(admins.includeAdmins());
        buf.writeBoolean(admins.temporary());
        buf.writeBoolean(admins.configured());
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
        int total = buf.readVarInt();
        int retention = buf.readVarInt();
        boolean enabled = buf.readBoolean();

        int players = Math.min(buf.readVarInt(), MAX_TRACKED);
        List<Track> tracks = new ArrayList<>(players);
        int left = MAX_TRACK_POINTS;
        for (int i = 0; i < players; i++) {
            String name = buf.readUtf(MAX_NAME);
            int count = Math.min(buf.readVarInt(), Math.max(0, left));
            List<PlayerTracks.Point> points = new ArrayList<>(count);
            for (int j = 0; j < count; j++) {
                points.add(new PlayerTracks.Point(
                    buf.readLong(), buf.readUtf(MAX_WHERE),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }
            left -= count;
            tracks.add(new Track(name, points));
        }

        ActivityLog.AdminPolicy admins = new ActivityLog.AdminPolicy(
            buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
        return new ActivityPayload(rows, total, retention, enabled, tracks, admins);
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
