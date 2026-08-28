package com.schecks.almin.client;

import com.schecks.almin.DirListingPayload;
import com.schecks.almin.DirRequestPayload;
import com.schecks.almin.FileUploadPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Modded-client file browser for /almin op dir. Each DirListingPayload from
 * the server opens a fresh browser for that directory; navigating sends a
 * DirRequestPayload and the reply re-opens the screen.
 *
 * Files can be pulled down with /almin op get, and — when the current folder
 * is an install folder — uploaded by dragging a file from the OS file manager
 * onto the window (see onFilesDrop / FileUploadPayload).
 */
public final class DirBrowserScreen extends Screen {
    private static final int ENTRY_HEIGHT = 14;

    private final String path;                            // relative to root, "" = root
    private final List<DirListingPayload.Entry> entries;
    private DirList list;
    private Button openButton;
    private Button downloadButton;
    private Button upButton;
    private Button uploadButton;
    private Button nanoButton;
    private Button deleteButton;

    public DirBrowserScreen(String path, List<DirListingPayload.Entry> entries) {
        super(Component.literal("Server files — /" + path));
        this.path = path;
        this.entries = entries;
    }

    @Override
    protected void init() {
        int listTop = 28;
        // Room for the tab strip above the file-action row.
        int listHeight = Math.max(ENTRY_HEIGHT, this.height - listTop - 70);

        list = new DirList(this.minecraft, this.width, listHeight, listTop, ENTRY_HEIGHT);
        for (DirListingPayload.Entry e : entries) {
            list.add(new DirEntry(e));
        }
        addRenderableWidget(list);

        int by = this.height - 28;
        upButton = Button.builder(Component.literal("Up"), b -> navigateUp())
            .bounds(4, by, 40, 20).build();
        openButton = Button.builder(Component.literal("Open"), b -> openSelected())
            .bounds(46, by, 46, 20).build();
        downloadButton = Button.builder(Component.literal("Download"), b -> downloadSelected())
            .bounds(94, by, 58, 20).build();
        nanoButton = Button.builder(Component.literal("Nano"), b -> nanoSelected())
            .bounds(154, by, 40, 20).build();
        deleteButton = Button.builder(Component.literal("Delete"), b -> deleteSelected())
            .bounds(196, by, 44, 20).build();
        uploadButton = Button.builder(Component.literal("Upload"), b -> uploadHint())
            .bounds(242, by, 46, 20).build();
        addRenderableWidget(upButton);
        addRenderableWidget(openButton);
        addRenderableWidget(downloadButton);
        addRenderableWidget(nanoButton);
        addRenderableWidget(deleteButton);
        addRenderableWidget(uploadButton);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(this.width - 48, by, 44, 20).build());

        for (var b : AlminNav.bar(this.width, by - 24, "Files", AlminNav::send)) {
            addRenderableWidget(b);
        }

        upButton.active = !path.isEmpty();
        uploadButton.active = isUploadable(path);
    }

    @Override
    public void onClose() {
        // Leaving for the game, not for another admin screen — drop the
        // dashboard breadcrumb so a later hand-typed command doesn't inherit it.
        AlminNav.leftAdminUi();
        super.onClose();
    }

    private DirListingPayload.Entry selected() {
        DirEntry e = list == null ? null : list.getSelected();
        return e == null ? null : e.data;
    }

    private void request(String newPath) {
        ClientPlayNetworking.send(new DirRequestPayload(newPath));
    }

    private void navigateUp() {
        if (path.isEmpty()) return;
        int slash = path.lastIndexOf('/');
        request(slash >= 0 ? path.substring(0, slash) : "");
    }

    private void openSelected() {
        DirListingPayload.Entry e = selected();
        if (e == null || !e.directory()) return;
        request(path.isEmpty() ? e.name() : path + "/" + e.name());
    }

    private void downloadSelected() {
        DirListingPayload.Entry e = selected();
        if (e == null || e.directory()) return;
        String full = path.isEmpty() ? e.name() : path + "/" + e.name();
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            this.minecraft.getConnection().sendCommand("almin op get " + full);
        }
        onClose();
    }

    /** Opens the selected file in /almin op nano. Server handles the rest. */
    private void nanoSelected() {
        DirListingPayload.Entry e = selected();
        if (e == null || e.directory()) return;
        String full = path.isEmpty() ? e.name() : path + "/" + e.name();
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            this.minecraft.getConnection().sendCommand("almin op nano " + full);
        }
        onClose();
    }

    /**
     * Asks for confirmation then runs /almin op delete on the selected entry.
     * Either choice re-requests the current listing so the browser refreshes
     * with the deletion applied (or unchanged on cancel).
     */
    private void deleteSelected() {
        DirListingPayload.Entry e = selected();
        if (e == null || this.minecraft == null) return;
        String full = path.isEmpty() ? e.name() : path + "/" + e.name();
        String confirm = e.directory()
            ? "Delete folder \"" + full + "\"? (must be empty)"
            : "Delete file \"" + full + "\"?";
        this.minecraft.setScreenAndShow(new ConfirmScreen(
            accepted -> {
                Minecraft mc = Minecraft.getInstance();
                if (accepted && mc.getConnection() != null) {
                    mc.getConnection().sendCommand("almin op delete " + full);
                }
                // Re-open the dir browser at the same path (refreshes on yes,
                // returns to the browser on no).
                ClientPlayNetworking.send(new DirRequestPayload(path));
            },
            Component.literal("Confirm delete"),
            Component.literal(confirm)
        ));
    }

    private void uploadHint() {
        chat("Drag a file from your file manager onto the Minecraft window "
            + "to upload it to /" + path + ".");
    }

    /**
     * Receives files drag-and-dropped onto the window. The dropped file is read
     * off-thread and sent to the server as a FileUploadPayload, destined for
     * the folder currently being browsed.
     */
    @Override
    public void onFilesDrop(List<Path> files) {
        if (files == null || files.isEmpty()) return;
        if (!isUploadable(path)) {
            chat("Can't upload here — open mods/, config/, datapacks/, "
                + "resourcepacks/ or shared/ first.");
            return;
        }
        Path file = files.get(0);
        CompletableFuture.runAsync(() -> {
            try {
                long size = Files.size(file);
                if (size > FileUploadPayload.MAX_BYTES) {
                    Minecraft.getInstance().execute(() ->
                        chat("File too large: " + size + " bytes (max 50 MB)."));
                    return;
                }
                byte[] data = Files.readAllBytes(file);
                String name = file.getFileName().toString();
                String dest = path + "/" + name;
                Minecraft.getInstance().execute(() -> {
                    ClientPlayNetworking.send(new FileUploadPayload(dest, data));
                    chat("Uploading " + name + " (" + data.length + " bytes) to /" + path + " ...");
                });
            } catch (IOException e) {
                Minecraft.getInstance().execute(() ->
                    chat("Could not read file: " + e.getMessage()));
            }
        });
    }

    private void chat(String message) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.literal(message));
        }
    }

    /** True if a file can be uploaded into {@code path} (an install folder). */
    private static boolean isUploadable(String path) {
        if (path.isEmpty()) return false;
        String[] seg = path.replace('\\', '/').split("/");
        if (seg.length == 0) return false;
        String first = seg[0];
        if (first.equals("mods") || first.equals("config")
                || first.equals("resourcepacks") || first.equals("shared")) {
            return true;
        }
        return seg.length >= 2 && seg[1].equals("datapacks");
    }

    /**
     * Right-click on an entry opens its context menu (Open/Download/Nano/
     * Rename/Delete). Other clicks fall through to the list/buttons.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 1 && list != null) {
            DirEntry hovered = list.entryAt(event.x(), event.y());
            if (hovered != null && this.minecraft != null) {
                list.setSelected(hovered);
                this.minecraft.setScreenAndShow(new EntryContextScreen(path, hovered.data));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        DirListingPayload.Entry sel = selected();
        openButton.active = sel != null && sel.directory();
        downloadButton.active = sel != null && !sel.directory();
        nanoButton.active = sel != null && !sel.directory();
        deleteButton.active = sel != null;
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        if (entries.isEmpty()) {
            g.centeredText(this.font, Component.literal("(empty directory)"),
                this.width / 2, this.height / 2, 0xFF888888);
        }
        if (isUploadable(path)) {
            g.centeredText(this.font,
                Component.literal("Drag a file onto the window to upload it here"),
                this.width / 2, this.height - 40, 0xFF888888);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }

    // ----- list widget -----

    private static final class DirList extends ObjectSelectionList<DirEntry> {
        DirList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        /** Public alias for the protected addEntry, callable from the screen. */
        void add(DirEntry entry) {
            addEntry(entry);
        }

        /** Exposes the protected entry-at-position lookup for the screen's right-click handler. */
        DirEntry entryAt(double mouseX, double mouseY) {
            return getEntryAtPosition(mouseX, mouseY);
        }
    }

    private static final class DirEntry extends ObjectSelectionList.Entry<DirEntry> {
        private final DirListingPayload.Entry data;

        DirEntry(DirListingPayload.Entry data) {
            this.data = data;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            String label = data.directory() ? data.name() + "/" : data.name();
            int color = data.directory() ? 0xFF55FFFF : 0xFFFFFFFF;
            g.text(mc.font, Component.literal(label),
                getContentX() + 2, getContentY() + 2, color, false);
            if (!data.directory()) {
                String size = humanBytes(data.size());
                int w = mc.font.width(size);
                g.text(mc.font, Component.literal(size),
                    getContentRight() - w - 2, getContentY() + 2, 0xFF777777, false);
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(data.name());
        }
    }
}
