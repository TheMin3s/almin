package com.schecks.almin.client;

import com.schecks.almin.PanelPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The screen behind the admin commands that used to answer only in chat:
 * masks, settings, advertised mods, shared files and updates.
 *
 * <p>One screen for all of them. The server sends rows and the command each
 * button should run ({@code AdminPanels}); this renders them and re-issues the
 * command, which the server re-checks exactly as if it had been typed. Nothing
 * here decides anything, which is why five screens' worth of surface needs no
 * five screens' worth of permission logic.
 *
 * <p>A row carrying an {@code input} opens a text box instead of firing
 * straight away — that is how {@code /almin config &lt;key&gt; &lt;value&gt;}
 * and {@code /almin mask set &lt;player&gt; &lt;name&gt;} are driven.
 */
public final class PanelScreen extends Screen {
    private static final int ROW_HEIGHT = 12;

    private static final int HEADER = 0xFFFFAA00;
    private static final int LABEL  = 0xFFAAAAAA;
    private static final int VALUE  = 0xFFFFFFFF;
    private static final int NOTE   = 0xFF777777;
    private static final int BTN_BG = 0xC02B3039;
    private static final int BTN_HI = 0xC03D4654;
    private static final int BTN_TX = 0xFFD9DEE5;

    /** Pixels of padding either side of a pseudo-button's label. */
    private static final int BTN_PAD = 5;

    private PanelPayload state;
    private RowList list;
    private EditBox filter;

    /** Set while a row's value is being edited; null the rest of the time. */
    private PanelPayload.Row editing;
    private EditBox editBox;

    private String pendingFilter = "";

    public PanelScreen(PanelPayload state) {
        super(Component.literal(state.title()));
        this.state = state;
    }

    /** Opens the screen, or re-seeds the open one from a fresh packet. */
    public static void show(PanelPayload state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof PanelScreen open) {
            open.pendingFilter = open.filter == null ? "" : open.filter.getValue();
            open.state = state;
            open.editing = null;
            open.rebuildWidgets();
            return;
        }
        mc.setScreenAndShow(new PanelScreen(state));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        filter = new EditBox(this.font, cx - 150, 28, 216, 18, Component.literal("Filter"));
        filter.setHint(Component.literal("filter"));
        filter.setMaxLength(64);
        filter.setValue(pendingFilter);
        filter.setResponder(v -> refill());
        addRenderableWidget(filter);

        addRenderableWidget(Button.builder(Component.literal("Refresh"),
                b -> AlminNav.send(state.refresh()))
            .bounds(cx + 72, 28, 78, 18).build());

        int top = 52;
        int bottom = editing != null ? 78 : 52;
        int height = Math.max(ROW_HEIGHT, this.height - top - bottom);
        list = new RowList(this.minecraft, this.width, height, top, ROW_HEIGHT);
        addRenderableWidget(list);
        refill();

        if (editing != null) {
            // The edit box replaces the tab strip while it is open, so the
            // value being typed is never hidden behind navigation.
            editBox = new EditBox(this.font, cx - 150, this.height - 74, 236, 20,
                Component.literal("Value"));
            editBox.setMaxLength(256);
            editBox.setValue(editing.input());
            editBox.setHint(Component.literal("new value"));
            addRenderableWidget(editBox);
            setInitialFocus(editBox);
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> commitEdit())
                .bounds(cx + 90, this.height - 74, 60, 20).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL,
                    b -> { editing = null; rebuildWidgets(); })
                .bounds(cx - 75, this.height - 28, 150, 20).build());
            return;
        }

        for (var b : AlminNav.bar(this.width, this.height - 52, tabFor(state.refresh()), AlminNav::send)) {
            addRenderableWidget(b);
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(cx - 75, this.height - 28, 150, 20).build());
    }

    /** Which nav tab this panel is, so the strip can show it as the current one. */
    static String tabFor(String refresh) {
        if (refresh.startsWith("almin config")) return "Config";
        if (refresh.startsWith("almin mods")) return "Mods";
        if (refresh.startsWith("almin mask")) return "Masks";
        if (refresh.startsWith("almin files")) return "Shared";
        if (refresh.startsWith("almin update")) return "Updates";
        return "";
    }

    private void refill() {
        if (list == null || state == null) return;
        list.clear();
        String q = filter == null ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);
        for (PanelPayload.Row r : state.rows()) {
            if (!matches(r, q)) continue;
            list.add(new RowEntry(this, r));
        }
    }

    /**
     * Headers and notes stay while filtering so the list keeps its shape;
     * only the rows a person is searching through are narrowed.
     */
    static boolean matches(PanelPayload.Row r, String q) {
        if (q.isEmpty() || r.kind() != PanelPayload.ENTRY) return true;
        return r.label().toLowerCase(Locale.ROOT).contains(q)
            || r.value().toLowerCase(Locale.ROOT).contains(q);
    }

    private void beginEdit(PanelPayload.Row row) {
        editing = row;
        rebuildWidgets();
    }

    private void commitEdit() {
        if (editing == null || editBox == null) return;
        String value = editBox.getValue().trim();
        String cmd = editing.cmd1();
        editing = null;
        if (!value.isEmpty()) AlminNav.send(cmd + " " + value);
        // The command's own reply refreshes the panel; ask anyway so a
        // rejected value still leaves the screen showing the truth.
        AlminNav.send(state.refresh());
    }

    void run(PanelPayload.Row row, int which) {
        if (which == 1 && !row.input().isEmpty()) {
            beginEdit(row);
            return;
        }
        String cmd = which == 1 ? row.cmd1() : row.cmd2();
        if (cmd.isEmpty()) return;
        AlminNav.send(cmd);
        AlminNav.send(state.refresh());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (editing == null && list != null && event.button() == 0) {
            RowEntry hovered = list.entryAt(event.x(), event.y());
            if (hovered != null && hovered.click(event.x(), event.y())) return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        AlminNav.leftAdminUi();
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 10, VALUE);
        if (editing != null) {
            g.text(this.font, Component.literal(editing.label()),
                this.width / 2 - 150, this.height - 86, HEADER, false);
            return;
        }
        if (!state.note().isEmpty()) {
            g.centeredText(this.font, Component.literal(
                    Text.fit(this.font, state.note(), this.width - 20)),
                this.width / 2, this.height - 42, NOTE);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class RowList extends ObjectSelectionList<RowEntry> {
        RowList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }
        void add(RowEntry e) { addEntry(e); }
        void clear() { clearEntries(); }
        RowEntry entryAt(double x, double y) { return getEntryAtPosition(x, y); }

        @Override
        public int getRowWidth() {
            return Math.min(this.width - 16, 460);
        }

        @Override
        public int getRowLeft() {
            return this.getX() + (this.width - getRowWidth()) / 2;
        }
    }

    private static final class RowEntry extends ObjectSelectionList.Entry<RowEntry> {
        private final PanelScreen screen;
        private final PanelPayload.Row row;

        /** Where the buttons were last drawn, for hit-testing. 0 width = absent. */
        private int b1x, b1w, b2x, b2w, by;

        RowEntry(PanelScreen screen, PanelPayload.Row row) {
            this.screen = screen;
            this.row = row;
        }

        /** True if the click landed on one of this row's buttons. */
        boolean click(double mx, double my) {
            if (my < by || my > by + 10) return false;
            if (b1w > 0 && mx >= b1x && mx <= b1x + b1w) { screen.run(row, 1); return true; }
            if (b2w > 0 && mx >= b2x && mx <= b2x + b2w) { screen.run(row, 2); return true; }
            return false;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int x = getContentX();
            int y = getContentY() + 1;
            int width = getContentWidth();
            by = y;
            b1w = 0;
            b2w = 0;

            if (row.kind() == PanelPayload.HEADER) {
                g.text(mc.font, Component.literal(Text.fit(mc.font, row.label(), width)),
                    x, y, HEADER, false);
                return;
            }
            if (row.kind() == PanelPayload.NOTE) {
                g.text(mc.font, Component.literal(Text.fit(mc.font, row.label(), width)),
                    x + 4, y, NOTE, false);
                return;
            }

            // Buttons first: they are fixed-width and everything else has to
            // fit in what is left, rather than running underneath them.
            int right = x + width;
            if (!row.btn2().isEmpty()) {
                b2w = mc.font.width(row.btn2()) + BTN_PAD * 2;
                b2x = right - b2w;
                right = b2x - 4;
                drawButton(g, mc, row.btn2(), b2x, y, b2w, mouseX, mouseY);
            }
            if (!row.btn1().isEmpty()) {
                b1w = mc.font.width(row.btn1()) + BTN_PAD * 2;
                b1x = right - b1w;
                right = b1x - 4;
                drawButton(g, mc, row.btn1(), b1x, y, b1w, mouseX, mouseY);
            }

            // Then the value, right-aligned in what remains, and the label in
            // whatever is left after that. Both are clipped, never overlapped.
            int budget = right - x;
            String value = Text.fit(mc.font, row.value(), Math.max(0, budget / 2));
            int valueWidth = mc.font.width(value);
            if (!value.isEmpty()) {
                g.text(mc.font, Component.literal(value), right - valueWidth, y,
                    row.accent() == 0 ? VALUE : row.accent(), false);
            }
            String label = Text.fit(mc.font, row.label(),
                Math.max(0, budget - valueWidth - 8));
            g.text(mc.font, Component.literal(label), x + 4, y, LABEL, false);
        }

        private void drawButton(GuiGraphicsExtractor g, Minecraft mc, String text,
                                int x, int y, int w, int mouseX, int mouseY) {
            boolean over = mouseX >= x && mouseX <= x + w && mouseY >= y - 1 && mouseY <= y + 10;
            g.fill(x, y - 1, x + w, y + 10, over ? BTN_HI : BTN_BG);
            g.text(mc.font, Component.literal(text), x + BTN_PAD, y + 1, BTN_TX, false);
        }

        @Override
        public Component getNarration() {
            return Component.literal(row.label() + " " + row.value());
        }
    }
}
