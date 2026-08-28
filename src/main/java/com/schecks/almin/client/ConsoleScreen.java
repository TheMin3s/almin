package com.schecks.almin.client;

import com.schecks.almin.ConsoleSubscribePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Live server-console viewer for trusted ops. Opens in response to
 * /almin op console (server sends ConsoleOpenPayload). The screen subscribes
 * to the feed on init, unsubscribes on close, and renders incoming lines as
 * they arrive — auto-scrolling to the bottom unless the user scrolled up.
 */
public final class ConsoleScreen extends Screen {
    private static final int ENTRY_HEIGHT = 10;
    private static final int MAX_LINES = 5000;

    private final Deque<String> buffer = new ArrayDeque<>();
    private ConsoleList list;
    private boolean subscribed = false;

    public ConsoleScreen() {
        super(Component.literal("Server Console"));
    }

    @Override
    protected void init() {
        int listTop = 24;
        int listHeight = Math.max(ENTRY_HEIGHT, this.height - listTop - 36);

        list = new ConsoleList(this.minecraft, this.width, listHeight, listTop, ENTRY_HEIGHT);
        for (String line : buffer) list.add(new LineEntry(line));
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(this.width / 2 - 75, this.height - 28, 150, 20)
            .build());

        if (!subscribed) {
            ClientPlayNetworking.send(new ConsoleSubscribePayload(true));
            subscribed = true;
        }

        list.setScrollAmount(list.maxScrollAmount());
    }

    @Override
    public void onClose() {
        if (subscribed) {
            ClientPlayNetworking.send(new ConsoleSubscribePayload(false));
            subscribed = false;
        }
        super.onClose();
    }

    /** Receives a batch of lines from the network. Called from AlminClient. */
    public static void appendLines(List<String> lines) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof ConsoleScreen cs) {
            cs.addLines(lines);
        }
    }

    private void addLines(List<String> lines) {
        if (lines.isEmpty()) return;
        boolean atBottom = list != null
            && list.scrollAmount() >= list.maxScrollAmount() - 1.0;

        for (String line : lines) {
            buffer.addLast(line);
            if (list != null) list.add(new LineEntry(line));
        }
        // Trim the oldest entries when the buffer overflows. Rebuilding the
        // list from the trimmed buffer is simpler than removing from the front.
        if (buffer.size() > MAX_LINES) {
            while (buffer.size() > MAX_LINES) buffer.pollFirst();
            if (list != null) {
                list.reset();
                for (String l : buffer) list.add(new LineEntry(l));
            }
        }
        if (atBottom && list != null) {
            list.setScrollAmount(list.maxScrollAmount());
        }
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

    // ----- list widget -----

    private static final class ConsoleList extends ObjectSelectionList<LineEntry> {
        ConsoleList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }
        void add(LineEntry e) { addEntry(e); }
        void reset()         { clearEntries(); }

        /** Widen rows to the full list (default is a centered ~220 px column,
         *  which clips long log lines). Leave a small inset for the scrollbar. */
        @Override
        public int getRowWidth() {
            return this.width - 8;
        }

        /** Anchor rows to the list's left edge instead of centering them, so
         *  console lines start at the screen's left edge. */
        @Override
        public int getRowLeft() {
            return this.getX();
        }
    }

    private static final class LineEntry extends ObjectSelectionList.Entry<LineEntry> {
        private final String line;

        LineEntry(String line) { this.line = line; }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int color = 0xFFCCCCCC;
            if (line.contains("/ERROR]") || line.contains(" ERROR ")) color = 0xFFFF6655;
            else if (line.contains("/WARN]") || line.contains(" WARN ")) color = 0xFFFFCC55;
            g.text(mc.font, Component.literal(line),
                getContentX() + 2, getContentY() + 1, color, false);
        }

        @Override
        public Component getNarration() {
            return Component.literal(line);
        }
    }
}
