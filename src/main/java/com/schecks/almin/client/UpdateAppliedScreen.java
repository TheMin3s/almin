package com.schecks.almin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Popup shown once when the client mod has auto-updated to match the server's
 * Almin version. The new jar is already in mods/ — this screen just makes
 * sure the player sees that a restart is needed to actually load it.
 */
public final class UpdateAppliedScreen extends Screen {
    private final String label;
    private final String fromVersion;
    private final String toVersion;

    public UpdateAppliedScreen(String fromVersion, String toVersion) {
        this("Almin", fromVersion, toVersion);
    }

    public UpdateAppliedScreen(String label, String fromVersion, String toVersion) {
        super(Component.literal(label + " Updated"));
        this.label = label;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        addRenderableWidget(Button.builder(Component.literal("Restart Minecraft Now"), b -> restart())
            .bounds(cx - 154, cy + 40, 150, 20)
            .build());
        addRenderableWidget(Button.builder(Component.literal("Later"), b -> openSkipConfirm())
            .bounds(cx + 4, cy + 40, 150, 20)
            .build());
    }

    /** First-screen Esc is disabled so skipping the restart isn't a one-tap. */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(null);
        }
    }

    private void restart() {
        if (this.minecraft != null) this.minecraft.stop();
    }

    private void openSkipConfirm() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(new UpdateRefuseConfirmScreen());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.centeredText(this.font, this.title, cx, cy - 50, 0xFFFFE17B);
        g.centeredText(this.font,
            Component.literal(label + " updated to v" + toVersion + " to match this server"),
            cx, cy - 24, 0xFFFFFFFF);
        g.centeredText(this.font,
            Component.literal("(previously v" + fromVersion + ")"),
            cx, cy - 10, 0xFFAAAAAA);
        g.centeredText(this.font,
            Component.literal("Restart Minecraft to apply it."),
            cx, cy + 14, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
