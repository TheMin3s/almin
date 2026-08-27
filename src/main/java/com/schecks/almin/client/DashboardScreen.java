package com.schecks.almin.client;

import com.schecks.almin.DashboardPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The {@code /almin} dashboard: the server's current state on one screen, plus
 * buttons through to the other admin screens.
 *
 * The rows arrive pre-measured and pre-formatted from the server (see
 * {@code Dashboard}), so this class only lays them out. Navigation re-issues
 * the ordinary commands, which means every destination is still gated
 * server-side — the {@code trusted} flag here just decides which buttons are
 * worth drawing.
 */
public final class DashboardScreen extends Screen {
    private static final int ENTRY_HEIGHT = 11;

    private static final int HEADER_COLOR = 0xFFFFAA00;
    private static final int LABEL_COLOR  = 0xFFAAAAAA;
    private static final int VALUE_COLOR  = 0xFFFFFFFF;
    private static final int NOTE_COLOR   = 0xFF777777;

    private final List<DashboardPayload.Row> rows;
    private final boolean trusted;
    private RowList list;

    public DashboardScreen(List<DashboardPayload.Row> rows, boolean trusted) {
        super(Component.literal("Almin — Dashboard"));
        this.rows = rows;
        this.trusted = trusted;
    }

    /** Replaces the open dashboard, or opens one if the screen isn't up. */
    public static void show(List<DashboardPayload.Row> rows, boolean trusted) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DashboardScreen(rows, trusted));
    }

    @Override
    protected void init() {
        int listTop = 24;
        int listHeight = Math.max(ENTRY_HEIGHT, this.height - listTop - 56);

        list = new RowList(this.minecraft, this.width, listHeight, listTop, ENTRY_HEIGHT);
        for (DashboardPayload.Row r : rows) list.add(new RowEntry(r));
        addRenderableWidget(list);

        // Two button rows: destinations on top, screen controls underneath.
        int navY = this.height - 50;
        int ctlY = this.height - 26;
        Nav nav = new Nav(navY);
        if (trusted) {
            nav.add("Console", "almin op console");
            nav.add("Files", "almin op dir");
        }
        nav.add("Shared", "almin files");
        nav.add("Masks", "almin mask list");
        nav.add("Config", "almin config");
        nav.add("Updates", "almin update version");
        nav.layout();

        // Refresh keeps the screen up — the reply payload replaces it in place.
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> send("almin"))
            .bounds(this.width / 2 - 76, ctlY, 74, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(this.width / 2 + 2, ctlY, 74, 20).build());
    }

    /**
     * Runs a command as if typed, closing the dashboard first. Destinations
     * that answer in chat would otherwise have their reply hidden behind this
     * screen; the ones that open their own screen replace it anyway.
     */
    private void run(String command) {
        onClose();
        send(command);
    }

    /** Runs a command without closing the dashboard. */
    private void send(String command) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.getConnection() == null) return;
        mc.getConnection().sendCommand(command);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Lays a variable number of equal-width buttons across the screen. */
    private final class Nav {
        private final List<String> labels = new java.util.ArrayList<>();
        private final List<String> commands = new java.util.ArrayList<>();
        private final int y;

        Nav(int y) { this.y = y; }

        void add(String label, String command) {
            labels.add(label);
            commands.add(command);
        }

        void layout() {
            if (labels.isEmpty()) return;
            int gap = 4;
            int total = Math.min(DashboardScreen.this.width - 8, 92 * labels.size());
            int w = (total - gap * (labels.size() - 1)) / labels.size();
            int x = (DashboardScreen.this.width - total) / 2;
            for (int i = 0; i < labels.size(); i++) {
                String command = commands.get(i);
                addRenderableWidget(Button.builder(Component.literal(labels.get(i)), b -> run(command))
                    .bounds(x + i * (w + gap), y, w, 20)
                    .build());
            }
        }
    }

    // ----- list widget -----

    private static final class RowList extends ObjectSelectionList<RowEntry> {
        RowList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }
        void add(RowEntry e) { addEntry(e); }

        /** A wide, left-anchored column — the default centred one clips values. */
        @Override
        public int getRowWidth() {
            return Math.min(this.width - 16, 420);
        }

        @Override
        public int getRowLeft() {
            return this.getX() + (this.width - getRowWidth()) / 2;
        }
    }

    private static final class RowEntry extends ObjectSelectionList.Entry<RowEntry> {
        private final DashboardPayload.Row row;

        RowEntry(DashboardPayload.Row row) { this.row = row; }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int x = getContentX();
            int y = getContentY() + 1;
            int width = getContentWidth();

            switch (row.kind()) {
                case DashboardPayload.HEADER -> g.text(mc.font,
                    Component.literal(row.label()), x, y, HEADER_COLOR, false);
                case DashboardPayload.NOTE -> g.text(mc.font,
                    Component.literal(row.label()), x + 6, y, NOTE_COLOR, false);
                default -> {
                    g.text(mc.font, Component.literal(row.label()), x + 6, y, LABEL_COLOR, false);
                    // Values are right-aligned so the numbers form a column.
                    int color = row.accent() == 0 ? VALUE_COLOR : row.accent();
                    int valueWidth = mc.font.width(row.value());
                    g.text(mc.font, Component.literal(row.value()),
                        x + width - valueWidth, y, color, false);
                }
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(row.value().isEmpty()
                ? row.label() : row.label() + ": " + row.value());
        }
    }
}
