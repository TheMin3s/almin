package com.schecks.almin.client;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.ActivityPayload;
import com.schecks.almin.ActivityRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The in-game Activity tab: what ordinary players have been doing.
 *
 * <p>The server decides who may see this — it only answers the request for a
 * trusted op, and the log never contains ops or trusted UUIDs in the first
 * place. This screen just draws what arrives.
 *
 * <p>The filter box is client-side only: it narrows the rows already sent, so
 * typing in it asks the server for nothing.
 */
public final class ActivityScreen extends Screen {
    private static final int ROW_HEIGHT = 22;

    private static final int TIME   = 0xFF6B7480;
    private static final int NAME   = 0xFFFFFFFF;
    private static final int DETAIL = 0xFF9AA3AE;
    private static final int NOTE   = 0xFF6B7480;
    private static final int WARN   = 0xFFFFCC55;

    /** Each action gets a colour so the list can be skimmed. */
    private static int actionColor(String action) {
        return switch (action) {
            case "chat"      -> 0xFF7FD1F0;
            case "command"   -> 0xFFFFAB33;
            case "container" -> 0xFFC792EA;
            case "death"     -> 0xFFE05A5A;
            case "attack"    -> 0xFFFF8A65;
            case "join"      -> 0xFF57C957;
            case "leave"     -> 0xFF8B9096;
            default          -> 0xFF9AA3AE;    // break / use
        };
    }

    private ActivityPayload state;
    private EditBox filter;
    private RowList list;
    private String pendingFilter = "";

    public ActivityScreen(ActivityPayload state) {
        super(Component.literal("Almin — Player activity"));
        this.state = state;
    }

    /** Opens the screen, or re-seeds the open one from a fresh packet. */
    public static void show(ActivityPayload state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof ActivityScreen open) {
            open.pendingFilter = open.filter == null ? "" : open.filter.getValue();
            open.state = state;
            open.rebuildWidgets();
            return;
        }
        mc.setScreenAndShow(new ActivityScreen(state));
    }

    public static void request() {
        ClientPlayNetworking.send(ActivityRequestPayload.GET);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        filter = new EditBox(this.font, cx - 150, 30, 216, 20,
            Component.literal("Filter"));
        filter.setHint(Component.literal("filter by player, action or detail"));
        filter.setMaxLength(64);
        filter.setValue(pendingFilter);
        filter.setResponder(v -> refill());
        addRenderableWidget(filter);

        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> request())
            .bounds(cx + 72, 30, 78, 20).build());

        int top = 56;
        int height = Math.max(ROW_HEIGHT, this.height - top - 56);
        list = new RowList(this.minecraft, this.width, height, top, ROW_HEIGHT);
        addRenderableWidget(list);
        refill();

        for (var b : AlminNav.bar(this.width, this.height - 52, "Activity", AlminNav::send)) {
            addRenderableWidget(b);
        }
        addRenderableWidget(Button.builder(Component.literal("Clear log"), b -> confirmClear())
            .bounds(cx - 155, this.height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(cx - 50, this.height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Settings"),
                b -> AlminNav.send("almin config"))
            .bounds(cx + 55, this.height - 28, 100, 20).build());
    }

    /** Applies the client-side filter to the rows already in hand. */
    private void refill() {
        if (list == null) return;
        list.clear();
        if (state == null) return;
        String q = filter == null ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);
        for (ActivityLog.Entry e : visible(state.entries(), q)) list.add(new RowEntry(e));
    }

    /** Rows matching {@code q} in any of the fields a person would search. */
    static List<ActivityLog.Entry> visible(List<ActivityLog.Entry> all, String q) {
        if (q == null || q.isEmpty()) return all;
        List<ActivityLog.Entry> out = new ArrayList<>();
        for (ActivityLog.Entry e : all) {
            if (e.player().toLowerCase(Locale.ROOT).contains(q)
                || e.action().toLowerCase(Locale.ROOT).contains(q)
                || e.detail().toLowerCase(Locale.ROOT).contains(q)
                || e.where().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
            }
        }
        return out;
    }

    private void confirmClear() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        mc.setScreenAndShow(new ConfirmScreen(
            yes -> {
                if (yes) ClientPlayNetworking.send(ActivityRequestPayload.CLEAR);
                mc.setScreenAndShow(this);
            },
            Component.literal("Clear the activity log?"),
            Component.literal("Every row is deleted from memory and from disk. This can't be undone.")));
    }

    @Override
    public void onClose() {
        AlminNav.leftAdminUi();
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 10, NAME);

        if (state == null) {
            g.centeredText(this.font, Component.literal("Waiting for the server…"),
                this.width / 2, 46, NOTE);
            return;
        }
        String sub;
        int color = NOTE;
        if (!state.enabled()) {
            sub = "Recording is off — activity-log is false, so nothing new is being kept.";
            color = WARN;
        } else if (state.total() == 0) {
            sub = "Nothing recorded yet. Ops and trusted UUIDs are never recorded.";
        } else {
            sub = state.total() + " row" + (state.total() == 1 ? "" : "s")
                + " · kept for " + humanMinutes(state.retentionMinutes())
                + (state.entries().size() < state.total()
                    ? " · showing the newest " + state.entries().size() : "");
        }
        g.centeredText(this.font, Component.literal(sub), this.width / 2, this.height - 42, color);
    }

    static String humanMinutes(int minutes) {
        if (minutes % 1440 == 0) {
            int d = minutes / 1440;
            return d + (d == 1 ? " day" : " days");
        }
        if (minutes % 60 == 0) {
            int h = minutes / 60;
            return h + (h == 1 ? " hour" : " hours");
        }
        return minutes + " minutes";
    }

    /** "3m ago", "2h ago" — a wall clock would need the server's timezone. */
    static String ago(long at) {
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
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
        private final ActivityLog.Entry row;

        RowEntry(ActivityLog.Entry row) { this.row = row; }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int x = getContentX();
            int y = getContentY() + 1;
            int width = getContentWidth();

            String when = ago(row.at());
            g.text(mc.font, Component.literal(when), x, y, TIME, false);
            int nameX = x + 30;
            g.text(mc.font, Component.literal(row.player()), nameX, y, NAME, false);

            String verb = row.action() + (row.count() > 1 ? " ×" + row.count() : "");
            int verbX = nameX + Math.max(76, mc.font.width(row.player()) + 8);
            g.text(mc.font, Component.literal(verb), verbX, y, actionColor(row.action()), false);

            // Second line carries the detail, clipped to the row rather than
            // running off it; the place goes on the right where there is room.
            int placeWidth = mc.font.width(row.where());
            boolean roomForPlace = width > placeWidth + 120;
            int detailRoom = width - (roomForPlace ? placeWidth + 10 : 0);
            g.text(mc.font, Component.literal(clip(mc, row.detail(), detailRoom)),
                x, y + 10, DETAIL, false);
            if (roomForPlace) {
                g.text(mc.font, Component.literal(row.where()),
                    x + width - placeWidth, y + 10, TIME, false);
            }
        }

        private static String clip(Minecraft mc, String text, int maxWidth) {
            if (text.isEmpty() || mc.font.width(text) <= maxWidth) return text;
            String cut = text;
            while (cut.length() > 1 && mc.font.width(cut + "…") > maxWidth) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + "…";
        }

        @Override
        public Component getNarration() {
            return Component.literal(row.player() + " " + row.action() + " " + row.detail());
        }
    }
}
