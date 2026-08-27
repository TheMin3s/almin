package com.schecks.almin.client;

import com.schecks.almin.FileTransferPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Client-side receiver for files the server offers via /almin get.
 *
 * The bytes arrive only after the player ran /almin get, so receiving them is
 * expected. Nothing touches disk until the player accepts a confirmation
 * screen, and the file always lands in a sandboxed downloads folder — never
 * mods/ or any path the server chose. The server-supplied filename is
 * stripped to a bare, character-sanitised base name.
 */
public final class FileDownloadHandler {
    /** Where accepted downloads are written: .minecraft/almin-downloads/ */
    private static final String DOWNLOAD_DIR = "almin-downloads";

    private FileDownloadHandler() {}

    /** Called on the client thread when a FileTransferPayload arrives. */
    public static void handle(FileTransferPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        String name = sanitize(payload.filename());
        byte[] data = payload.data();
        String size = humanSize(data.length);

        ConfirmScreen screen = new ConfirmScreen(
            accepted -> {
                if (accepted) {
                    save(mc, name, data);
                } else {
                    mc.setScreen(null);
                }
            },
            Component.literal("Almin — Download file?"),
            Component.literal("The server is offering \"" + name + "\" (" + size
                + "). If you accept, it will be saved to " + downloadDir()
                + " — nothing is run automatically.")
        );
        mc.setScreen(screen);
    }

    /** Absolute path of the downloads folder: &lt;.minecraft&gt;/almin-downloads/ */
    private static Path downloadDir() {
        return FabricLoader.getInstance().getGameDir()
            .resolve(DOWNLOAD_DIR).toAbsolutePath().normalize();
    }

    private static void save(Minecraft mc, String name, byte[] data) {
        Path dir = downloadDir();
        try {
            Files.createDirectories(dir);
            Path dest = dir.resolve(name);
            Path tmp = Files.createTempFile(dir, ".almin-dl-", ".part");
            Files.write(tmp, data);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            mc.setScreen(null);
            chat(mc, Component.literal("Saved to " + dest)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        } catch (IOException e) {
            mc.setScreen(null);
            chat(mc, Component.literal("Failed to save file: " + e.getMessage())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
        }
    }

    private static void chat(Minecraft mc, Component message) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(message);
        }
    }

    /** Reduces a server-supplied name to a safe bare filename. */
    private static String sanitize(String raw) {
        String n = raw.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.isBlank() || n.equals(".") || n.equals("..")) n = "download.bin";
        return n;
    }

    private static String humanSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }
}
