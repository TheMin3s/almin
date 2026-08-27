package com.schecks.almin.client;

import com.schecks.almin.DashboardPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
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

    // Same palette as the web panel, so the two views read as one product.
    private static final int TILE_BG      = 0xC0181B21;
    private static final int TILE_LINE    = 0xFF2B3039;
    private static final int TRACK        = 0xFF272C35;
    private static final int GOOD         = 0xFF0CA30C;
    private static final int WARN         = 0xFFFAB219;
    private static final int CRIT         = 0xFFD03B3B;
    private static final int CAP_COLOR    = 0xFF6B7480;

    private static final int TILE_H = 34;
    private static final int TILES_TOP = 22;

    private final List<DashboardPayload.Row> rows;
    private final DashboardPayload.Tiles tiles;
    private final boolean trusted;
    private RowList list;

    public DashboardScreen(List<DashboardPayload.Row> rows, DashboardPayload.Tiles tiles, boolean trusted) {
        super(Component.literal("Almin — Dashboard"));
        this.rows = rows;
        this.tiles = tiles;
        this.trusted = trusted;
    }

    /** Replaces the open dashboard, or opens one if the screen isn't up. */
    public static void show(List<DashboardPayload.Row> rows, DashboardPayload.Tiles tiles, boolean trusted) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DashboardScreen(rows, tiles, trusted));
    }

    @Override
    protected void init() {
        int listTop = TILES_TOP + TILE_H + 8;
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
        nav.add("Mods", "almin mods list");
        nav.add("Masks", "almin mask list");
        nav.add("Config", "almin config");
        nav.add("Updates", "almin update version");
        nav.layout();

        // Refresh keeps the screen up — the reply payload replaces it in place.
        // Stop is trusted-only and confirmed; the server re-checks regardless.
        // There is deliberately no Start here: stopping the server disconnects
        // this client, so nothing in game would be left to press it.
        if (trusted) {
            addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> send("almin"))
                .bounds(this.width / 2 - 116, ctlY, 74, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Stop server"), b -> confirmStop())
                .bounds(this.width / 2 - 38, ctlY, 78, 20).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 + 44, ctlY, 74, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> send("almin"))
                .bounds(this.width / 2 - 76, ctlY, 74, 20).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 + 2, ctlY, 74, 20).build());
        }
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

    /** Stopping the server drops everyone, so it asks first. */
    private void confirmStop() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        mc.setScreen(new ConfirmScreen(
            yes -> {
                if (yes) {
                    send("almin op restart");
                    onClose();
                } else {
                    mc.setScreen(this);
                }
            },
            Component.literal("Stop the server?"),
            Component.literal("Every player, including you, will be disconnected.")));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 7, 0xFFFFFFFF);
        drawTiles(g);
    }

    /**
     * The KPI strip: the same four headline numbers the web panel leads with.
     * Status colours always sit next to a word ("healthy", "high"), so the
     * state is never carried by colour alone.
     */
    private void drawTiles(GuiGraphicsExtractor g) {
        if (tiles == null) return;
        int gap = 4;
        int total = Math.min(this.width - 16, 460);
        int w = (total - gap * 3) / 4;
        int x0 = (this.width - total) / 2;
        int y = TILES_TOP;

        double tps = tiles.tps();
        float target = tiles.tpsTarget() <= 0 ? 20f : tiles.tpsTarget();
        int tpsColor = tps >= target - 0.5 ? GOOD : tps >= target * 0.75 ? WARN : CRIT;
        String tpsWord = tps >= target - 0.5 ? "healthy" : tps >= target * 0.75 ? "strained" : "critical";
        tile(g, x0, y, w, "TPS", String.format(java.util.Locale.ROOT, "%.2f", tps),
            tpsWord, tpsColor, (int) Math.round(Math.min(100, tps / target * 100)), tpsColor);

        int pPct = tiles.maxPlayers() > 0 ? tiles.players() * 100 / tiles.maxPlayers() : 0;
        tile(g, x0 + (w + gap), y, w, "PLAYERS",
            tiles.players() + " / " + tiles.maxPlayers(), "", VALUE_COLOR, pPct, HEADER_COLOR);

        int mp = tiles.memPct();
        int memColor = mp >= 90 ? CRIT : mp >= 75 ? WARN : GOOD;
        String memWord = mp >= 90 ? "critical" : mp >= 75 ? "high" : "normal";
        tile(g, x0 + (w + gap) * 2, y, w, "MEMORY", mp + "%", memWord, memColor, mp, memColor);

        tile(g, x0 + (w + gap) * 3, y, w, "UPTIME", tiles.uptime(), "", VALUE_COLOR, -1, 0);
    }

    private void tile(GuiGraphicsExtractor g, int x, int y, int w,
                      String caption, String value, String word, int valueColor,
                      int meterPct, int meterColor) {
        g.fill(x, y, x + w, y + TILE_H, TILE_BG);
        g.fill(x, y, x + w, y + 1, TILE_LINE);                      // top rule
        g.fill(x, y + TILE_H - 1, x + w, y + TILE_H, TILE_LINE);    // bottom rule
        g.text(this.font, Component.literal(caption), x + 4, y + 4, CAP_COLOR, false);
        g.text(this.font, Component.literal(value), x + 4, y + 14, valueColor, false);
        if (!word.isEmpty()) {
            int vw = this.font.width(value);
            g.text(this.font, Component.literal(word), x + 6 + vw, y + 14, valueColor, false);
        }
        if (meterPct >= 0) {
            int mx = x + 4, my = y + TILE_H - 7, mw = w - 8;
            g.fill(mx, my, mx + mw, my + 3, TRACK);
            int filled = Math.max(0, Math.min(mw, mw * Math.min(100, meterPct) / 100));
            if (filled > 0) g.fill(mx, my, mx + filled, my + 3, meterColor);
        }
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
