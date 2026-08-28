package com.schecks.almin.client;

import com.schecks.almin.WebAdminPayload;
import com.schecks.almin.WebAdminRequestPayload;
import com.schecks.almin.WebControlPayload;
import com.schecks.almin.WebPasswordPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The in-game Web tab: what the web panel is doing, the controls to run it,
 * and the settings worth changing from a game client.
 *
 * <p>Nothing here decides anything. Every button sends a
 * {@link WebControlPayload} and the server re-checks {@code TrustedOps} before
 * acting — the screen only opens in the first place because the server chose
 * to answer the status request.
 *
 * <p>Two settings are deliberately absent. The password hash has its own path
 * ({@link WebPasswordPayload}) that never carries a plaintext through chat or
 * the log, and {@code web-start-command} is the one setting that becomes a
 * command on the host OS, so it stays behind {@code /almin config}.
 */
public final class WebPanelScreen extends Screen {
    private static final int LABEL = 0xFFAAAAAA;
    private static final int VALUE = 0xFFFFFFFF;
    private static final int GOOD  = 0xFF57C957;
    private static final int WARN  = 0xFFFFCC55;
    private static final int BAD   = 0xFFE05A5A;
    private static final int DIM   = 0xFF9AA3AE;

    // ---- layout, kept as plain arithmetic so it can be checked off-game ----
    /** Height of one control row plus the gap under it. */
    static final int ROW = 24;
    /** Top of the first row of controls, below the two status lines. */
    static final int TOP_ROW = 44;
    /** Run controls, two toggle rows, the settings row, the password row. */
    static final int ROWS = 5;
    /** Narrow enough for a small window, wide enough for the settings row. */
    static final int MIN_CONTENT = 180;
    static final int MAX_CONTENT = 320;

    static int contentWidth(int screenWidth) {
        return Math.max(MIN_CONTENT, Math.min(MAX_CONTENT, screenWidth - 20));
    }
    static int contentX(int screenWidth) {
        return (screenWidth - contentWidth(screenWidth)) / 2;
    }
    static int rowY(int index) { return TOP_ROW + ROW * index; }
    /** Bottom edge of the last control row. */
    static int contentBottom() { return rowY(ROWS - 1) + 20; }
    /** Where the shared tab strip goes; nothing above may reach it. */
    static int navY(int screenHeight) { return screenHeight - 52; }

    private WebAdminPayload state;
    private EditBox password;
    private EditBox portBox;
    private EditBox bindBox;
    private EditBox minsBox;

    /** Survives a refresh, so an in-progress password isn't wiped by a reply. */
    private String pendingPassword = "";

    private String message = "";
    private int messageColor = DIM;

    public WebPanelScreen(WebAdminPayload state) {
        super(Component.literal("Almin — Web panel"));
        this.state = state;
    }

    /** Opens the screen, or re-seeds the open one from a fresh status packet. */
    public static void show(WebAdminPayload state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof WebPanelScreen open) {
            // The settings boxes follow the server's truth — if a change was
            // rejected, the box should say so rather than keep the rejected
            // value. A half-typed password is the one thing worth preserving.
            open.pendingPassword = open.password == null ? "" : open.password.getValue();
            open.state = state;
            open.rebuildWidgets();
            return;
        }
        mc.setScreenAndShow(new WebPanelScreen(state));
    }

    /** Asks the server to open this screen. */
    public static void request() {
        ClientPlayNetworking.send(WebAdminRequestPayload.INSTANCE);
    }

    private static void act(String action) {
        ClientPlayNetworking.send(WebControlPayload.action(action));
    }

    private static void set(String key, String value) {
        ClientPlayNetworking.send(WebControlPayload.set(key, value));
    }

    private static void toggle(String key, boolean current) {
        set(key, current ? "false" : "true");
    }

    @Override
    protected void init() {
        boolean running = state != null && state.running();
        boolean enabled = state != null && state.enabled();

        int cw = contentWidth(this.width);
        int x0 = contentX(this.width);
        int y = rowY(0);

        // --- run controls ---
        int quarter = (cw - 9) / 4;
        addRenderableWidget(button("Start", x0, y, quarter,
            "Start the web panel now, without restarting the server.",
            b -> act("start"))).active = !running && enabled;
        addRenderableWidget(button("Stop", x0 + quarter + 3, y, quarter,
            "Stop serving. The Minecraft server keeps running.",
            b -> act("stop"))).active = running;
        addRenderableWidget(button("Restart", x0 + (quarter + 3) * 2, y, quarter,
            "Stop and start again — how a changed port or address takes effect.",
            b -> act("restart"))).active = running;
        addRenderableWidget(button("Refresh", x0 + (quarter + 3) * 3, y, cw - (quarter + 3) * 3,
            "Ask the server for the panel's current state.",
            b -> request()));

        // --- toggles ---
        y += ROW;
        int half = (cw - 3) / 2;
        addRenderableWidget(button(flag("Enabled", enabled), x0, y, half,
            "Whether the panel runs at all, now and on every future start.",
            b -> toggle("web-ui-enabled", enabled)));
        addRenderableWidget(button(flag("Public metrics", state != null && state.publicMetrics()),
            x0 + half + 3, y, cw - half - 3,
            "Serve the small no-login view: versions, uptime, player count, TPS.",
            b -> toggle("web-public-metrics", state != null && state.publicMetrics())));

        y += ROW;
        addRenderableWidget(button(flag("HTTPS only", state != null && state.requireSecure()), x0, y, half,
            "Refuse admin login unless the connection is loopback or HTTPS via a proxy. "
                + "Turn this on only once TLS is actually in front of the panel.",
            b -> toggle("web-require-secure", state != null && state.requireSecure())));
        addRenderableWidget(button(flag("Outlive server", state != null && state.supervisor()),
            x0 + half + 3, y, cw - half - 3,
            "Keep the panel up after the Minecraft server stops, so it can start it again. "
                + "Needs web-start-command set in the config file.",
            b -> toggle("web-supervisor", state != null && state.supervisor())));

        // --- port / address / session, applied together ---
        y += ROW;
        int applyW = 54;
        int numW = 42;
        int bindW = cw - numW * 2 - applyW - 9;
        portBox = field(x0, y, numW, "port", state == null ? "" : String.valueOf(state.configuredPort()));
        bindBox = field(x0 + numW + 3, y, bindW, "bind address",
            state == null ? "" : state.bind());
        minsBox = field(x0 + numW + bindW + 6, y, numW, "mins",
            state == null ? "" : String.valueOf(state.sessionMinutes()));
        addRenderableWidget(button("Apply", x0 + cw - applyW, y, applyW,
            "Port, address the panel binds to, and how long a web login lasts. "
                + "0.0.0.0 means every interface; 127.0.0.1 means this machine only.",
            b -> applySettings()));

        // --- password ---
        y += ROW;
        int setW = 96;
        password = field(x0, y, cw - setW - 3, "new web password (8+ characters)", pendingPassword);
        password.setMaxLength(128);
        addRenderableWidget(button(state != null && state.passwordSet() ? "Replace pw" : "Set password",
            x0 + cw - setW, y, setW,
            "Sent on the game connection, not through chat — so it never reaches the server log.",
            b -> submitPassword()));

        for (var b : AlminNav.bar(this.width, navY(this.height), "Web", AlminNav::send)) {
            addRenderableWidget(b);
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(this.width / 2 - 75, this.height - 28, 150, 20).build());
    }

    private static String flag(String label, boolean on) {
        return label + ": " + (on ? "on" : "off");
    }

    private Button button(String label, int x, int y, int w, String tip, Button.OnPress onPress) {
        return Button.builder(Component.literal(label), onPress)
            .bounds(x, y, w, 20)
            .tooltip(Tooltip.create(Component.literal(tip)))
            .build();
    }

    private EditBox field(int x, int y, int w, String hint, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        return box;
    }

    /**
     * Sends only what the operator actually changed, so pressing Apply with one
     * edited box doesn't churn the other two settings — and doesn't restart the
     * listener for no reason.
     */
    private void applySettings() {
        if (state == null) return;
        int sent = 0;
        String port = portBox.getValue().trim();
        if (!port.equals(String.valueOf(state.configuredPort()))) {
            if (!isNumber(port)) { say("Port must be a whole number.", WARN); return; }
            set("web-ui-port", port);
            sent++;
        }
        String bind = bindBox.getValue().trim();
        if (!bind.isEmpty() && !bind.equals(state.bind())) {
            set("web-ui-bind", bind);
            sent++;
        }
        String mins = minsBox.getValue().trim();
        if (!mins.equals(String.valueOf(state.sessionMinutes()))) {
            if (!isNumber(mins)) { say("Session length must be a whole number of minutes.", WARN); return; }
            set("web-session-minutes", mins);
            sent++;
        }
        if (sent == 0) say("Nothing changed.", DIM);
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty() || s.length() > 6) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private void submitPassword() {
        String value = password.getValue();
        if (value.length() < 8) {
            say("Too short — use at least 8 characters.", WARN);
            return;
        }
        ClientPlayNetworking.send(new WebPasswordPayload(value));
        password.setValue("");
        pendingPassword = "";
        say("Sent. The server will confirm in chat.", GOOD);
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    @Override
    public void onClose() {
        AlminNav.leftAdminUi();
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        int cw = contentWidth(this.width);
        int x = contentX(this.width);

        if (state == null) {
            g.text(this.font, Component.literal("Waiting for the server…"), x, 24, DIM, false);
            return;
        }

        String status = state.running() ? "running" : (state.enabled() ? "not running" : "off");
        int statusColor = state.running() ? GOOD : (state.enabled() ? BAD : WARN);
        g.text(this.font, Component.literal("Status"), x, 22, LABEL, false);
        g.text(this.font, Component.literal(status), x + 78, 22, statusColor, false);
        // Only when there is room for it; the button below says the same thing.
        if (cw >= 260) {
            g.text(this.font, Component.literal(
                    state.passwordSet() ? "password set" : "no password — nobody can log in"),
                x + 150, 22, state.passwordSet() ? DIM : WARN, false);
        }

        // Second line is the single most useful fact: where to point a browser,
        // or why there is nowhere to point it.
        if (state.running()) {
            g.text(this.font, Component.literal("Address"), x, 32, LABEL, false);
            g.text(this.font, Component.literal(trim(state.url(), cw - 78)), x + 78, 32, VALUE, false);
        } else if (!state.lastError().isEmpty()) {
            g.text(this.font, Component.literal(trim(state.lastError(), cw - 4)), x, 32, BAD, false);
        } else if (!state.enabled()) {
            g.text(this.font, Component.literal("Turn Enabled on to serve the panel."), x, 32, DIM, false);
        } else {
            g.text(this.font, Component.literal(trim("Would serve " + state.url(), cw - 4)), x, 32, DIM, false);
        }

        int noteY = this.height - 66;
        if (!message.isEmpty()) {
            g.centeredText(this.font, Component.literal(message), this.width / 2, noteY, messageColor);
        } else if (this.height >= 250) {
            g.text(this.font, Component.literal(
                    "Changes save immediately; port and address restart the listener."),
                x, noteY - 10, DIM, false);
            g.text(this.font, Component.literal(
                    "The password box shows what you type — mind who is watching."),
                x, noteY, DIM, false);
        }
    }

    /** Cuts a message to the width available rather than letting it run off. */
    private String trim(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        String cut = text;
        while (cut.length() > 1 && this.font.width(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
