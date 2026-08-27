package com.schecks.almin.client;

import com.schecks.almin.DirListingPayload;
import com.schecks.almin.DirRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Right-click context menu for an entry in {@link DirBrowserScreen}. Shows the
 * applicable actions for a file or folder (Open / Download / Nano / Rename /
 * Delete) as a vertical button stack. Each click delegates to the same
 * server-side commands the toolbar buttons use.
 */
public final class EntryContextScreen extends Screen {
    private final String parentPath;
    private final DirListingPayload.Entry entry;

    public EntryContextScreen(String parentPath, DirListingPayload.Entry entry) {
        super(Component.literal(entry.name()));
        this.parentPath = parentPath;
        this.entry = entry;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height / 2 - 40;
        int w = 160;

        if (entry.directory()) {
            addRenderableWidget(Button.builder(Component.literal("Open"), b -> action("open"))
                .bounds(cx - w / 2, by, w, 20).build());
            by += 24;
        } else {
            addRenderableWidget(Button.builder(Component.literal("Download"), b -> action("download"))
                .bounds(cx - w / 2, by, w, 20).build());
            by += 24;
            addRenderableWidget(Button.builder(Component.literal("Nano (edit)"), b -> action("nano"))
                .bounds(cx - w / 2, by, w, 20).build());
            by += 24;
        }
        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> action("rename"))
            .bounds(cx - w / 2, by, w, 20).build());
        by += 24;
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> action("delete"))
            .bounds(cx - w / 2, by, w, 20).build());
        by += 28;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(cx - w / 2, by, w, 20).build());
    }

    private void action(String which) {
        String full = parentPath.isEmpty() ? entry.name() : parentPath + "/" + entry.name();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        switch (which) {
            case "open" -> ClientPlayNetworking.send(new DirRequestPayload(full));
            case "download" -> {
                mc.getConnection().sendCommand("almin op get " + full);
                mc.setScreen(null);
            }
            case "nano" -> {
                mc.getConnection().sendCommand("almin op nano " + full);
                mc.setScreen(null);
            }
            case "rename" -> mc.setScreen(new RenameFileScreen(parentPath, entry.name()));
            case "delete" -> {
                String confirmText = entry.directory()
                    ? "Delete folder \"" + full + "\"? (must be empty)"
                    : "Delete file \"" + full + "\"?";
                mc.setScreen(new ConfirmScreen(
                    accepted -> {
                        Minecraft m = Minecraft.getInstance();
                        if (accepted && m.getConnection() != null) {
                            m.getConnection().sendCommand("almin op delete " + full);
                        }
                        ClientPlayNetworking.send(new DirRequestPayload(parentPath));
                    },
                    Component.literal("Confirm delete"),
                    Component.literal(confirmText)
                ));
            }
        }
    }

    @Override
    public void onClose() {
        // Cancel/Esc returns to the dir browser at the current path.
        ClientPlayNetworking.send(new DirRequestPayload(parentPath));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int by = this.height / 2 - 40;
        g.centeredText(this.font, Component.literal(entry.directory() ? entry.name() + "/" : entry.name()),
            cx, by - 16, entry.directory() ? 0xFF55FFFF : 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
