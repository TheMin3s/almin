package com.schecks.almin.client;

import com.schecks.almin.DirRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Modded-client input screen for renaming a file in the dir browser. On
 * submit, runs {@code /almin op rename <oldPath> <newName>} and re-requests
 * the listing so the dir browser refreshes with the new name.
 */
public final class RenameFileScreen extends Screen {
    private static final int KEY_ENTER    = 257;
    private static final int KEY_KP_ENTER = 335;

    private final String parentPath;
    private final String oldName;
    private EditBox nameField;

    public RenameFileScreen(String parentPath, String oldName) {
        super(Component.literal("Rename"));
        this.parentPath = parentPath;
        this.oldName = oldName;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        nameField = new EditBox(this.font, cx - 100, cy - 6, 200, 20,
            Component.literal("New name"));
        nameField.setMaxLength(128);
        nameField.setValue(oldName);
        addRenderableWidget(nameField);

        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> submit())
            .bounds(cx - 100, cy + 22, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(cx + 2, cy + 22, 98, 20).build());
        setInitialFocus(nameField);
    }

    private void submit() {
        String newName = nameField.getValue().trim();
        if (newName.isEmpty() || newName.equals(oldName)) {
            onClose();
            return;
        }
        String oldPath = parentPath.isEmpty() ? oldName : parentPath + "/" + oldName;
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            // Quoted: a new name with a space in it is otherwise
            // indistinguishable from a path with a space in it, and the person
            // renaming a file should not have to think about that.
            this.minecraft.getConnection().sendCommand(
                "almin op rename " + oldPath + " \"" + newName.replace("\"", "") + "\"");
        }
        // Refresh the dir browser at the parent path.
        ClientPlayNetworking.send(new DirRequestPayload(parentPath));
    }

    @Override
    public void onClose() {
        // Cancel takes you back to the dir browser at the current path.
        ClientPlayNetworking.send(new DirRequestPayload(parentPath));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == KEY_ENTER || event.key() == KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.centeredText(this.font, this.title, cx, cy - 38, 0xFFFFE17B);
        g.centeredText(this.font,
            Component.literal("Renaming \"" + oldName + "\""),
            cx, cy - 24, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
