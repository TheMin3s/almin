package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -&gt; client: a list-shaped admin screen.
 *
 * <h3>Why one payload for five screens</h3>
 * Masks, config, advertised mods, shared files and updates are all the same
 * shape — rows, each with a label, a value, and one or two things you can do to
 * it. Rather than five payloads and five screens, the server builds the rows
 * (see {@code AdminPanels}) and each button carries the ordinary command that
 * performs it. The client renders and re-issues; it decides nothing, and the
 * server re-checks permission on the command exactly as if it had been typed.
 *
 * <p>A row whose {@code input} is non-empty means the first button needs a
 * value: the client opens an edit box prefilled with it and appends what was
 * typed to the command. That is how {@code /almin config <key> <value>} and
 * {@code /almin mask set <player> <name>} are driven without a bespoke screen
 * for either.
 *
 * <p>Hand-written codec — the row is past what {@code StreamCodec.composite}
 * builds, and a list of them certainly is.
 */
public record PanelPayload(String title, String note, String refresh, List<Row> rows)
        implements CustomPacketPayload {

    /** Row kinds, matching {@code DashboardPayload} so the two read alike. */
    public static final int HEADER = 0;
    public static final int ENTRY = 1;
    public static final int NOTE = 2;

    /**
     * One line.
     *
     * @param accent ARGB for the value, or 0 for the default
     * @param cmd1   command the first button runs, "" for no button
     * @param input  prefill for the first button's edit box, "" for a plain click
     */
    public record Row(int kind, String label, String value, int accent,
                      String btn1, String cmd1, String btn2, String cmd2, String input) {

        public static Row header(String label) {
            return new Row(HEADER, label, "", 0, "", "", "", "", "");
        }
        public static Row note(String label) {
            return new Row(NOTE, label, "", 0, "", "", "", "", "");
        }
        public static Row of(String label, String value, int accent) {
            return new Row(ENTRY, label, value, accent, "", "", "", "", "");
        }
    }

    public static final int MAX_ROWS = 400;
    private static final int MAX_TEXT = 200;
    private static final int MAX_CMD = 256;

    public static final int MAX_BYTES = MAX_ROWS * (MAX_TEXT * 3 + MAX_CMD * 2) * 4 + 16384;

    public static final CustomPacketPayload.Type<PanelPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:panel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PanelPayload> CODEC =
        StreamCodec.of(PanelPayload::write, PanelPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, PanelPayload p) {
        buf.writeUtf(clip(p.title, MAX_TEXT), MAX_TEXT);
        buf.writeUtf(clip(p.note, MAX_TEXT), MAX_TEXT);
        buf.writeUtf(clip(p.refresh, MAX_CMD), MAX_CMD);
        int n = Math.min(p.rows.size(), MAX_ROWS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Row r = p.rows.get(i);
            buf.writeVarInt(r.kind());
            buf.writeUtf(clip(r.label(), MAX_TEXT), MAX_TEXT);
            buf.writeUtf(clip(r.value(), MAX_TEXT), MAX_TEXT);
            buf.writeInt(r.accent());
            buf.writeUtf(clip(r.btn1(), 32), 32);
            buf.writeUtf(clip(r.cmd1(), MAX_CMD), MAX_CMD);
            buf.writeUtf(clip(r.btn2(), 32), 32);
            buf.writeUtf(clip(r.cmd2(), MAX_CMD), MAX_CMD);
            buf.writeUtf(clip(r.input(), MAX_TEXT), MAX_TEXT);
        }
    }

    private static PanelPayload read(RegistryFriendlyByteBuf buf) {
        String title = buf.readUtf(MAX_TEXT);
        String note = buf.readUtf(MAX_TEXT);
        String refresh = buf.readUtf(MAX_CMD);
        int n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new Row(
                buf.readVarInt(),
                buf.readUtf(MAX_TEXT),
                buf.readUtf(MAX_TEXT),
                buf.readInt(),
                buf.readUtf(32),
                buf.readUtf(MAX_CMD),
                buf.readUtf(32),
                buf.readUtf(MAX_CMD),
                buf.readUtf(MAX_TEXT)));
        }
        return new PanelPayload(title, note, refresh, rows);
    }

    /** Truncates rather than throwing: a long label is a bad row, not a dead packet. */
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public CustomPacketPayload.Type<PanelPayload> type() {
        return TYPE;
    }
}
