package com.schecks.almin.client;

import com.schecks.almin.NanoOpenPayload;
import com.schecks.almin.NanoSavePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Modded-client text editor for /almin op nano. The server pushes a file's
 * contents (NanoOpenPayload); Save sends the edited text back
 * (NanoSavePayload) for the server to write. Esc / Cancel discards.
 */
public final class NanoEditorScreen extends Screen {
    private final String path;
    private final String initialContent;
    private MultiLineEditBox editor;

    public NanoEditorScreen(String path, String content) {
        super(Component.literal("nano — " + shortName(path)));
        this.path = path;
        this.initialContent = content;
    }

    private static String shortName(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    @Override
    protected void init() {
        int margin = 30;
        int top = 28;
        int editorHeight = Math.max(40, this.height - top - 44);

        editor = MultiLineEditBox.builder()
            .setX(margin)
            .setY(top)
            .setPlaceholder(Component.literal("(empty file)"))
            .build(this.font, this.width - margin * 2, editorHeight,
                Component.literal("File contents"));
        editor.setValue(this.initialContent);
        addRenderableWidget(editor);

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
            .bounds(this.width / 2 - 154, this.height - 32, 150, 20)
            .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(this.width / 2 + 4, this.height - 32, 150, 20)
            .build());

        setInitialFocus(editor);
    }

    private void save() {
        String text = editor.getValue();
        if (text.length() > NanoOpenPayload.MAX_CHARS) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal(
                    "Too large to save: " + text.length() + " chars (limit "
                        + NanoOpenPayload.MAX_CHARS + ")."));
            }
            return;
        }
        ClientPlayNetworking.send(new NanoSavePayload(path, text));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
