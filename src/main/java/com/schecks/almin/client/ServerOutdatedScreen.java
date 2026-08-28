package com.schecks.almin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Popup shown when the connected server is running an older Almin than the
 * client — networking payloads may not line up, so the player should tell the
 * server owner to update. Read-only; only the OK button closes it.
 */
public final class ServerOutdatedScreen extends Screen {
    private final String serverVersion;
    private final String clientVersion;

    public ServerOutdatedScreen(String serverVersion, String clientVersion) {
        super(Component.literal("Server Almin Out of Date"));
        this.serverVersion = serverVersion;
        this.clientVersion = clientVersion;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(cx - 75, cy + 50, 150, 20)
            .build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(null);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.centeredText(this.font, this.title, cx, cy - 58, 0xFFFF8855);
        g.centeredText(this.font,
            Component.literal("This server runs Almin v" + serverVersion),
            cx, cy - 30, 0xFFFFFFFF);
        g.centeredText(this.font,
            Component.literal("Your client is on v" + clientVersion),
            cx, cy - 16, 0xFFAAAAAA);
        g.centeredText(this.font,
            Component.literal("Some features may not work correctly."),
            cx, cy + 4, 0xFFAAAAAA);
        g.centeredText(this.font,
            Component.literal("Ask the server owner to update Almin."),
            cx, cy + 22, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
