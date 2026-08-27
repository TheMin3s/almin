package com.schecks.almin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * What happened after the player approved a server's mod offer: one line per
 * mod, so a partial failure is visible rather than silently swallowed.
 */
public final class ModOfferResultScreen extends Screen {
    private final List<ClientModInstaller.Outcome> results;
    private final boolean allOk;

    public ModOfferResultScreen(List<ClientModInstaller.Outcome> results, boolean allOk) {
        super(Component.literal(allOk ? "Mods installed" : "Some mods didn't install"));
        this.results = results;
        this.allOk = allOk;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(this.width / 2 - 75, this.height - 32, 150, 20).build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Minecraft mc = Minecraft.getInstance();
        g.centeredText(mc.font, this.title, this.width / 2, 22, allOk ? 0xFF57C957 : 0xFFFFCC55);
        int y = 46;
        for (ClientModInstaller.Outcome r : results) {
            g.centeredText(mc.font,
                Component.literal((r.ok() ? "✔ " : "✖ ") + r.modId() + " — " + r.detail()),
                this.width / 2, y, r.ok() ? 0xFFCCCCCC : 0xFFFF7A6B);
            y += 12;
        }
        g.centeredText(mc.font,
            Component.literal("Restart Minecraft to load them. Nothing was loaded into this session."),
            this.width / 2, y + 10, 0xFF9AA3AE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
