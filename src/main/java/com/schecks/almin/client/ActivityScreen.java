package com.schecks.almin.client;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.ActivityPayload;
import com.schecks.almin.ActivityRequestPayload;
import com.schecks.almin.PlayerTracks;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
            case "place"     -> 0xFF66C2A5;
            case "break"     -> 0xFFE8A33D;
            default          -> 0xFF9AA3AE;    // use / anything new
        };
    }

    private ActivityPayload state;
    private EditBox filter;
    private RowList list;
    private String pendingFilter = "";

    // ---- the map at the top ----

    /**
     * Kept across rebuilds, and across closing and reopening the screen: an
     * operator who hid the map, or who was looking at the Nether, should not
     * have to say so again every time a refresh arrives.
     */
    private static boolean mapShown = true;
    private static String mapDim = "";
    private static double cursor = 1.0;

    /** The map's height, when there is room for one at all. */
    private static final int MAP_H = 104;
    private static final int MAP_MIN_ROOM = 260;

    /** How far back from the cursor an action still counts as "just now". */
    private static final double MARKER_WINDOW = 0.06;

    private static final int MAP_BG   = 0xFF0E1116;
    private static final int MAP_GRID = 0xFF1B1F27;
    private static final int MAP_EDGE = 0xFF262B34;

    private TimeSlider timeline;
    private int mapX, mapY, mapW;

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

        filter = new EditBox(this.font, cx - 155, 26, 160, 20,
            Component.literal("Filter"));
        filter.setHint(Component.literal("filter rows"));
        filter.setMaxLength(64);
        filter.setValue(pendingFilter);
        filter.setResponder(v -> refill());
        addRenderableWidget(filter);

        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> request())
            .bounds(cx + 10, 26, 66, 20).build());

        boolean room = this.height >= MAP_MIN_ROOM;
        addRenderableWidget(Button.builder(
                Component.literal(mapShown && room ? "Hide map" : "Show map"),
                b -> { mapShown = !mapShown; rebuildWidgets(); })
            .bounds(cx + 80, 26, 75, 20).build());

        addAdminButtons(cx);

        int top = 70;
        boolean drawMap = mapShown && room && state != null;
        if (drawMap) {
            mapW = Math.min(this.width - 40, 460);
            mapX = cx - mapW / 2;
            mapY = top;
            timeline = new TimeSlider(mapX, mapY + MAP_H + 2, mapW, 16, cursor);
            addRenderableWidget(timeline);
            top = mapY + MAP_H + 22;
        } else {
            timeline = null;
        }

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

    /**
     * The two ways to change who is in the log.
     *
     * <p>Two buttons rather than one cycling through three states, because
     * "saved" and "until the next restart" are genuinely different decisions
     * and a cycle would hide which one you just made.
     */
    private void addAdminButtons(int cx) {
        ActivityLog.AdminPolicy p = state == null ? null : state.admins();
        boolean on = p != null && p.includeAdmins();
        boolean saved = p != null && p.configured();
        boolean temp = p != null && p.temporary();

        Button savedButton = Button.builder(
                Component.literal(saved ? "Admins: recorded" : "Admins: excluded"),
                b -> ClientPlayNetworking.send(ActivityRequestPayload.admins(!saved)))
            .bounds(cx - 155, 48, 150, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                "The saved setting (activity-include-admins). Ops and trusted UUIDs are "
                + "left out by default, so the log is not a way for staff to watch each other.")))
            .build();
        savedButton.active = p != null;
        addRenderableWidget(savedButton);

        Button tempButton = Button.builder(
                Component.literal(temp ? (on ? "This run: recorded" : "This run: excluded")
                                       : "This run: follow"),
                b -> ClientPlayNetworking.send(ActivityRequestPayload.adminsTemp(
                    temp ? null : !on)))
            .bounds(cx + 5, 48, 150, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                "Overrides the setting until the server restarts, then forgets — which is "
                + "usually what an investigation actually needs. Press again to hand control "
                + "back to the setting.")))
            .build();
        tempButton.active = p != null;
        addRenderableWidget(tempButton);
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
        g.centeredText(this.font, Component.literal(Text.fit(this.font, sub, this.width - 20)),
            this.width / 2, this.height - 42, color);

        if (timeline != null) drawMap(g);
    }

    // ---------- the map ----------

    /**
     * Everyone on one clock: where each tracked player had been by the moment
     * the timeline points at, and what happened around it.
     *
     * <p>The same picture the web panel draws, with the same rule about
     * dimensions — one at a time, because overworld and nether coordinates
     * share numbers but not places, and overlaying them would be a lie.
     */
    private void drawMap(GuiGraphicsExtractor g) {
        g.fill(mapX, mapY, mapX + mapW, mapY + MAP_H, MAP_BG);
        g.outline(mapX, mapY, mapW, MAP_H, MAP_EDGE);

        List<ActivityPayload.Track> tracks = state.tracks() == null ? List.of() : state.tracks();
        List<ActivityLog.Entry> acts = new ArrayList<>();
        for (ActivityLog.Entry e : state.entries()) {
            if (e.dim() != null && !e.dim().isEmpty()) acts.add(e);
        }

        Set<String> dims = new LinkedHashSet<>();
        for (ActivityPayload.Track t : tracks) {
            for (PlayerTracks.Point pt : t.points()) if (!pt.dim().isEmpty()) dims.add(pt.dim());
        }
        for (ActivityLog.Entry e : acts) dims.add(e.dim());
        if (dims.isEmpty()) {
            g.centeredText(this.font, Component.literal("No positions recorded yet."),
                mapX + mapW / 2, mapY + MAP_H / 2 - 4, NOTE);
            return;
        }
        if (mapDim.isEmpty() || !dims.contains(mapDim)) mapDim = dims.iterator().next();

        long from = Long.MAX_VALUE, to = 0;
        for (ActivityPayload.Track t : tracks) {
            for (PlayerTracks.Point pt : t.points()) {
                from = Math.min(from, pt.at()); to = Math.max(to, pt.at());
            }
        }
        for (ActivityLog.Entry e : acts) { from = Math.min(from, e.at()); to = Math.max(to, e.at()); }
        if (from == Long.MAX_VALUE) from = to;
        long at = from + (long) ((to - from) * cursor);
        long window = Math.max(1L, (long) ((to - from) * MARKER_WINDOW));

        // Bounds over everything in this dimension, so the view does not jump
        // about as the cursor moves.
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ActivityPayload.Track t : tracks) {
            for (PlayerTracks.Point pt : t.points()) {
                if (!mapDim.equals(pt.dim())) continue;
                minX = Math.min(minX, pt.x()); maxX = Math.max(maxX, pt.x());
                minZ = Math.min(minZ, pt.z()); maxZ = Math.max(maxZ, pt.z());
            }
        }
        for (ActivityLog.Entry e : acts) {
            if (!mapDim.equals(e.dim())) continue;
            minX = Math.min(minX, e.x()); maxX = Math.max(maxX, e.x());
            minZ = Math.min(minZ, e.z()); maxZ = Math.max(maxZ, e.z());
        }
        if (minX == Integer.MAX_VALUE) {
            g.centeredText(this.font, Component.literal("Nothing in " + mapDim + "."),
                mapX + mapW / 2, mapY + MAP_H / 2 - 4, NOTE);
            return;
        }
        double span = Math.max(Math.max(maxX - minX, maxZ - minZ), 16) * 1.12;
        double cxW = (minX + maxX) / 2.0, czW = (minZ + maxZ) / 2.0;
        int innerX = mapX + 2, innerY = mapY + 2, innerW = mapW - 4, innerH = MAP_H - 4;

        for (int i = 1; i < 4; i++) {
            g.verticalLine(innerX + innerW * i / 4, innerY, innerY + innerH, MAP_GRID);
            g.horizontalLine(innerX, innerX + innerW, innerY + innerH * i / 4, MAP_GRID);
        }

        for (ActivityPayload.Track t : tracks) {
            int color = playerColor(t.player());
            int px = Integer.MIN_VALUE, py = 0, lastX = 0, lastY = 0;
            boolean any = false;
            for (PlayerTracks.Point pt : t.points()) {
                if (!mapDim.equals(pt.dim()) || pt.at() > at) continue;
                int sx = innerX + (int) ((pt.x() - cxW) / span * innerW + innerW / 2.0);
                int sy = innerY + (int) ((pt.z() - czW) / span * innerH + innerH / 2.0);
                if (px != Integer.MIN_VALUE) line(g, px, py, sx, sy, color);
                px = sx; py = sy; lastX = sx; lastY = sy; any = true;
            }
            // Where they were when the cursor points.
            if (any) g.fill(lastX - 2, lastY - 2, lastX + 3, lastY + 3, 0xFF000000 | color);
        }

        for (ActivityLog.Entry e : acts) {
            if (!mapDim.equals(e.dim()) || e.at() > at || e.at() < at - window) continue;
            int sx = innerX + (int) ((e.x() - cxW) / span * innerW + innerW / 2.0);
            int sy = innerY + (int) ((e.z() - czW) / span * innerH + innerH / 2.0);
            g.fill(sx - 2, sy - 2, sx + 3, sy + 3, actionColor(e.action()));
            g.fill(sx - 1, sy - 1, sx + 2, sy + 2, 0xFFFFFFFF);
        }

        String caption = mapDim + " · " + Math.round(span) + " blocks across · "
            + tracks.size() + " tracked";
        g.text(this.font, Component.literal(Text.fit(this.font, caption, mapW - 8)),
            mapX + 4, mapY + MAP_H - 11, TIME, false);
        if (dims.size() > 1) {
            String hint = "click to switch dimension";
            g.text(this.font, Component.literal(hint),
                mapX + mapW - this.font.width(hint) - 4, mapY + 4, TIME, false);
        }
    }

    /**
     * Cycles the dimension when the map itself is clicked.
     *
     * <p>A click rather than another button: the screen is already full, and
     * the map is the only thing the choice applies to.
     */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (timeline != null && state != null) {
            double mx = event.x(), my = event.y();
            if (mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + MAP_H) {
                cycleDim();
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private void cycleDim() {
        Set<String> dims = new LinkedHashSet<>();
        if (state.tracks() != null) {
            for (ActivityPayload.Track t : state.tracks()) {
                for (PlayerTracks.Point pt : t.points()) if (!pt.dim().isEmpty()) dims.add(pt.dim());
            }
        }
        for (ActivityLog.Entry e : state.entries()) {
            if (e.dim() != null && !e.dim().isEmpty()) dims.add(e.dim());
        }
        if (dims.size() < 2) return;
        List<String> order = new ArrayList<>(dims);
        int i = order.indexOf(mapDim);
        mapDim = order.get((i + 1) % order.size());
    }

    /** A straight line as filled pixels — the GUI has no line primitive that takes two points. */
    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int rgb) {
        int color = 0xFF000000 | rgb;
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        // A path that jumps a long way is a teleport or a gap in sampling, not
        // a walk; drawing it would invent a journey nobody made.
        if (steps > 400) { g.fill(x2, y2, x2 + 1, y2 + 1, color); return; }
        if (steps == 0) { g.fill(x1, y1, x1 + 1, y1 + 1, color); return; }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** Stable per-player colour, so the same person is the same line every time. */
    static int playerColor(String name) {
        int h = 0;
        for (int i = 0; i < name.length(); i++) h = h * 31 + name.charAt(i);
        return hsv((Math.floorMod(h, 360)) / 360f, 0.55f, 0.92f);
    }

    private static int hsv(float h, float s, float v) {
        int i = (int) (h * 6) % 6;
        float f = h * 6 - (int) (h * 6);
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    /** The timeline under the map. */
    private final class TimeSlider extends AbstractSliderButton {
        TimeSlider(int x, int y, int w, int h, double initial) {
            super(x, y, w, h, Component.empty(), initial);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(value >= 0.999 ? "now" : "back " + backLabel()));
        }

        private String backLabel() {
            if (state == null) return "";
            long from = Long.MAX_VALUE, to = 0;
            for (ActivityLog.Entry e : state.entries()) {
                from = Math.min(from, e.at()); to = Math.max(to, e.at());
            }
            if (from == Long.MAX_VALUE) return "";
            long at = from + (long) ((to - from) * value);
            return ago(at);
        }

        @Override
        protected void applyValue() {
            cursor = value;
        }
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

            // The verb sits in a fixed column so the actions line up; the name
            // gets the space before it and is cut short if it needs more.
            String verb = row.action() + (row.count() > 1 ? " ×" + row.count() : "");
            int verbWidth = mc.font.width(verb);
            int nameX = x + 30;
            int verbX = Math.max(nameX + 80, x + width - verbWidth);
            g.text(mc.font,
                Component.literal(Text.fit(mc.font, row.player(), verbX - nameX - 6)),
                nameX, y, NAME, false);
            g.text(mc.font, Component.literal(verb), verbX, y, actionColor(row.action()), false);

            // Second line carries the detail; the place goes on the right when
            // there is room for it, and the detail takes what is left.
            int placeWidth = mc.font.width(row.where());
            boolean roomForPlace = width > placeWidth + 120;
            int detailRoom = width - (roomForPlace ? placeWidth + 10 : 0);
            g.text(mc.font, Component.literal(Text.fit(mc.font, row.detail(), detailRoom)),
                x, y + 10, DETAIL, false);
            if (roomForPlace) {
                g.text(mc.font, Component.literal(row.where()),
                    x + width - placeWidth, y + 10, TIME, false);
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(row.player() + " " + row.action() + " " + row.detail());
        }
    }
}
