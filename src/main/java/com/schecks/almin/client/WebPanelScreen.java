package com.schecks.almin.client;

import com.schecks.almin.WebAdminPayload;
import com.schecks.almin.WebAdminRequestPayload;
import com.schecks.almin.WebPasswordPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The in-game Web tab: what the web panel is doing, and a place to set its
 * password without typing it into chat.
 *
 * <p>Chat is the wrong channel for a password — it is visible on screen, it
 * goes into the server log, and it sits in the client's chat history. The field
 * here sends {@link WebPasswordPayload} on the game connection instead, and the
 * server hashes it on arrival.
 *
 * <p>The screen only opens for a trusted op, because that is the only case in
 * which the server answers the status request at all.
 */
public final class WebPanelScreen extends Screen {
    private static final int LABEL = 0xFFAAAAAA;
    private static final int VALUE = 0xFFFFFFFF;
    private static final int GOOD  = 0xFF57C957;
    private static final int WARN  = 0xFFFFCC55;
    private static final int DIM   = 0xFF9AA3AE;

    private WebAdminPayload state;
    private EditBox password;
    private String message = "";
    private int messageColor = DIM;

    public WebPanelScreen(WebAdminPayload state) {
        super(Component.literal("Almin — Web panel"));
        this.state = state;
    }

    /** Opens (or refreshes) the screen with a status packet from the server. */
    public static void show(WebAdminPayload state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof WebPanelScreen open) {
            open.state = state;          // refresh in place, keep any typing
            open.rebuildMessageForState();
            return;
        }
        mc.setScreenAndShow(new WebPanelScreen(state));
    }

    /** Asks the server to open this screen. */
    public static void request() {
        ClientPlayNetworking.send(WebAdminRequestPayload.INSTANCE);
    }

    private void rebuildMessageForState() {
        if (state != null && state.passwordSet() && message.isEmpty()) {
            message = "A password is set — you can log in on the web panel.";
            messageColor = DIM;
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 118;

        password = new EditBox(this.font, cx - 150, y, 300, 20,
            Component.literal("New web password"));
        password.setMaxLength(128);
        password.setHint(Component.literal("new password (at least 8 characters)"));
        // Not a real password field — Minecraft has none — so it stays visible.
        // Said plainly on screen rather than left as a surprise.
        addRenderableWidget(password);

        addRenderableWidget(Button.builder(
                Component.literal(state != null && state.passwordSet() ? "Replace password" : "Set password"),
                b -> submit())
            .bounds(cx - 150, y + 26, 146, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> request())
            .bounds(cx + 4, y + 26, 146, 20).build());

        for (var b : AlminNav.bar(this.width, this.height - 52, "Web", AlminNav::send)) {
            addRenderableWidget(b);
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(cx - 75, this.height - 28, 150, 20).build());
    }

    private void submit() {
        String value = password.getValue();
        if (value.length() < 8) {
            message = "Too short — use at least 8 characters.";
            messageColor = WARN;
            return;
        }
        ClientPlayNetworking.send(new WebPasswordPayload(value));
        password.setValue("");
        message = "Sent. The server will confirm in chat.";
        messageColor = GOOD;
    }

    @Override
    public void onClose() {
        AlminNav.leftAdminUi();
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        int x = this.width / 2 - 150;
        int y = 34;
        if (state == null) {
            g.text(this.font, Component.literal("Waiting for the server…"), x, y, DIM, false);
            return;
        }
        row(g, x, y,      "Status",    state.running() ? "running" : "not running",
            state.running() ? GOOD : WARN);
        row(g, x, y += 12, "Address",  state.running() ? state.url() : "—", VALUE);
        row(g, x, y += 12, "Binding",  state.bind() + ":" + state.port(), VALUE);
        row(g, x, y += 12, "Password", state.passwordSet() ? "set" : "not set — nobody can log in",
            state.passwordSet() ? GOOD : WARN);
        row(g, x, y += 12, "Public metrics", state.publicMetrics() ? "on" : "off", VALUE);
        row(g, x, y += 12, "HTTPS required", state.requireSecure() ? "yes" : "no", VALUE);

        g.text(this.font, Component.literal(
                "Typed here, the password never goes through chat or the server log."),
            x, y + 16, DIM, false);
        g.text(this.font, Component.literal(
                "It is shown as you type, so mind who is watching."),
            x, y + 26, DIM, false);

        if (!message.isEmpty()) {
            g.centeredText(this.font, Component.literal(message),
                this.width / 2, this.height - 68, messageColor);
        }
    }

    private void row(GuiGraphicsExtractor g, int x, int y, String label, String value, int color) {
        g.text(this.font, Component.literal(label), x, y, LABEL, false);
        g.text(this.font, Component.literal(value), x + 110, y, color, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
