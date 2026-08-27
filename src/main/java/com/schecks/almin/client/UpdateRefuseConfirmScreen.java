package com.schecks.almin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Second confirmation shown when the player chooses "Later" on
 * {@link UpdateAppliedScreen}. Two clicks (or click + Esc) are required to
 * skip the restart — one to choose "Later", one to confirm here — so it's a
 * deliberate decision rather than reflex.
 */
public final class UpdateRefuseConfirmScreen extends Screen {
    public UpdateRefuseConfirmScreen() {
        super(Component.literal("Skip the restart?"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        addRenderableWidget(Button.builder(Component.literal("Restart Minecraft Now"), b -> restart())
            .bounds(cx - 154, cy + 40, 150, 20)
            .build());
        addRenderableWidget(Button.builder(Component.literal("Skip Anyway"), b -> onClose())
            .bounds(cx + 4, cy + 40, 150, 20)
            .build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    private void restart() {
        if (this.minecraft != null) this.minecraft.stop();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.centeredText(this.font, this.title, cx, cy - 44, 0xFFFFAA55);
        g.centeredText(this.font,
            Component.literal("Your client keeps running the old version"),
            cx, cy - 18, 0xFFCCCCCC);
        g.centeredText(this.font,
            Component.literal("until you restart Minecraft."),
            cx, cy - 6, 0xFFCCCCCC);
        g.centeredText(this.font,
            Component.literal("Skip anyway?"),
            cx, cy + 14, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
