package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * File delivery for the /almin get commands.
 *
 *  - /almin get <name>      — anyone — serves from the server's shared/ folder.
 *  - /almin op get <path>   — admin  — serves any path under the server root.
 *
 * A folder target is zipped on the fly. 8 MB cap (the FileTransferPayload
 * limit). Players are confined to shared/ so they can't pull world data,
 * configs, or other server files.
 */
public final class FileShare {
    public static final int MAX_BYTES = FileTransferPayload.MAX_BYTES;

    private static volatile Path serverRoot;
    private static volatile Path sharedDir;

    private FileShare() {}

    /** Registers the large S2C transfer payload. Call once from the main entrypoint. */
    public static void registerPayload() {
        PayloadTypeRegistry.clientboundPlay().registerLarge(
            FileTransferPayload.TYPE, FileTransferPayload.CODEC,
            FileTransferPayload.MAX_BYTES + 4096);
    }

    /** Resolves paths and ensures shared/ exists. Call at server start. */
    public static void init(MinecraftServer server) {
        serverRoot = server.getServerDirectory().toAbsolutePath().normalize();
        sharedDir = serverRoot.resolve("shared");
        try {
            Files.createDirectories(sharedDir);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not create shared/ folder: {}", e.getMessage());
        }
    }

    /** Immediate contents of shared/ (files and folders, dirs first). */
    public static List<Path> listShared() {
        List<Path> out = new ArrayList<>();
        if (sharedDir == null || !Files.isDirectory(sharedDir)) return out;
        try (Stream<Path> s = Files.list(sharedDir)) {
            s.forEach(out::add);
        } catch (IOException ignored) {}
        out.sort(Comparator
            .comparing((Path p) -> !Files.isDirectory(p))
            .thenComparing(p -> p.getFileName().toString().toLowerCase()));
        return out;
    }

    /** Resolves a name within shared/ — traversal outside shared/ is rejected. */
    public static Path resolveShared(String name) {
        if (sharedDir == null) return null;
        Path p = sharedDir.resolve(name).toAbsolutePath().normalize();
        if (!p.startsWith(sharedDir) || !Files.exists(p)) return null;
        return p;
    }

    /** Resolves any path under the server root — admin use only. */
    public static Path resolveRoot(String relativePath) {
        if (serverRoot == null) return null;
        Path p = serverRoot.resolve(relativePath).toAbsolutePath().normalize();
        if (!p.startsWith(serverRoot) || !Files.exists(p)) return null;
        return p;
    }

    /**
     * Sends a file — or a zipped folder — to a player's client. Returns
     * "ok:&lt;bytes&gt;" or "error:&lt;reason&gt;".
     */
    public static String sendTo(ServerPlayer player, Path target) {
        if (target == null) return "error:no such file or folder";
        if (!ServerPlayNetworking.canSend(player, FileTransferPayload.TYPE)) {
            return "error:you need the Almin client mod installed to receive files";
        }
        byte[] data;
        String name;
        try {
            if (Files.isDirectory(target)) {
                data = zipFolder(target);
                name = target.getFileName() + ".zip";
            } else {
                if (Files.size(target) > MAX_BYTES) return "error:file too large (max 8 MB)";
                data = Files.readAllBytes(target);
                name = target.getFileName().toString();
            }
        } catch (IOException e) {
            return "error:" + e.getMessage();
        }
        if (data.length > MAX_BYTES) {
            return "error:too large to send (max 8 MB, got " + data.length + ")";
        }
        ServerPlayNetworking.send(player, new FileTransferPayload(name, data));
        return "ok:" + data.length;
    }

    private static byte[] zipFolder(Path folder) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos);
             Stream<Path> walk = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isDirectory(p)) continue;
                String entry = folder.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entry));
                Files.copy(p, zos);
                zos.closeEntry();
                if (bos.size() > MAX_BYTES) {
                    throw new IOException("folder too large to zip (max 8 MB)");
                }
            }
        }
        return bos.toByteArray();
    }
}
