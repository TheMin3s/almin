package com.schecks.almin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Filesystem operations behind the web panel, enforcing exactly the same rules
 * the in-game {@code /almin op} file commands do:
 *
 * <ul>
 *   <li>every path is resolved against the server directory and rejected if it
 *       escapes it (so {@code ../} can't walk out);</li>
 *   <li>reads and listings are allowed anywhere under the server root;</li>
 *   <li>writes, deletes and renames are limited to the configured writable
 *       roots (plus each world's {@code datapacks/}), and never the mod's own
 *       jar.</li>
 * </ul>
 *
 * All of this is the same policy as the commands — the web panel is another
 * front-end onto it, not a second, looser one. Every method here touches the
 * disk and must run on the server thread (the panel calls them via
 * {@code server.submit}).
 */
public final class WebFiles {
    /** A read cap so the panel can't try to stream a multi-GB file into a browser. */
    public static final long MAX_READ_BYTES = 2 * 1024 * 1024;
    /** Matching cap on writes. */
    public static final long MAX_WRITE_BYTES = 8 * 1024 * 1024;
    /** Cap on how many entries a single listing returns, so a huge directory
     *  can't produce a runaway response. */
    public static final int MAX_LIST_ENTRIES = 2000;

    public record Entry(String name, boolean directory, long size) {}
    public record Listing(String path, boolean isDir, long fileSize, List<Entry> entries) {}
    public record Result(boolean ok, String message) {
        static Result pass()          { return new Result(true, "ok"); }
        static Result fail(String m)  { return new Result(false, m); }
    }

    private WebFiles() {}

    private static Path root(MinecraftServer server) {
        return server.getServerDirectory().toAbsolutePath().normalize();
    }

    /** Resolves {@code rel} under the server root, or null if it escapes. */
    public static Path resolveSafe(MinecraftServer server, String rel) {
        return resolveUnder(root(server), rel);
    }

    /**
     * Resolves {@code rel} under {@code root}, returning null if it escapes.
     * Server-free so the traversal guard can be exercised on its own.
     */
    public static Path resolveUnder(Path root, String rel) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(rel == null ? "" : rel).toAbsolutePath().normalize();
        return target.startsWith(base) ? target : null;
    }

    /**
     * True if {@code rel} lands somewhere writes are permitted: under one of the
     * configured writable roots, or a world's {@code datapacks/} folder. Mirrors
     * the guard on {@code /almin op delete|rename}.
     */
    public static boolean isWritable(MinecraftServer server, Path target) {
        Path root = root(server);
        if (target.equals(root)) return false;
        Path rel = root.relativize(target);
        if (rel.getNameCount() == 0) return false;
        String top = rel.getName(0).toString();
        if (AlminConfig.get().dirWritableRootsAsSet().contains(top)) return true;
        // Any world's datapacks dir, e.g. world/datapacks.
        Path datapacks = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
        return target.startsWith(datapacks.getParent());
    }

    private static boolean isOwnJar(Path target) {
        Path own = UpdateChecker.ownJarPath();
        return own != null && own.toAbsolutePath().normalize().equals(target);
    }

    /** Lists a directory, or returns file metadata for a file. */
    public static Listing list(MinecraftServer server, String rel) throws IOException {
        Path target = resolveSafe(server, rel);
        if (target == null) throw new IOException("Path escapes server directory");
        if (!Files.exists(target)) throw new IOException("No such path: " + rel);
        if (!Files.isDirectory(target)) {
            long size = Files.size(target);
            return new Listing(rel, false, size, List.of());
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> s = Files.list(target)) {
            s.forEach(paths::add);
        }
        paths.sort(Comparator
            .comparing((Path p) -> !Files.isDirectory(p))
            .thenComparing(p -> p.getFileName().toString().toLowerCase()));
        List<Entry> entries = new ArrayList<>(Math.min(paths.size(), MAX_LIST_ENTRIES));
        for (Path p : paths) {
            if (entries.size() >= MAX_LIST_ENTRIES) break;
            boolean dir = Files.isDirectory(p);
            long size = -1;
            if (!dir) { try { size = Files.size(p); } catch (IOException ignored) {} }
            entries.add(new Entry(p.getFileName().toString(), dir, size));
        }
        return new Listing(rel, true, -1, entries);
    }

    /** Reads a text file's contents, capped at {@link #MAX_READ_BYTES}. */
    public static String read(MinecraftServer server, String rel) throws IOException {
        Path target = resolveSafe(server, rel);
        if (target == null) throw new IOException("Path escapes server directory");
        if (!Files.isRegularFile(target)) throw new IOException("Not a regular file: " + rel);
        long size = Files.size(target);
        if (size > MAX_READ_BYTES) {
            throw new IOException("File too large to open here (" + size + " bytes)");
        }
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    /** Writes text to a file under a writable root. Creates parent dirs. */
    public static Result write(MinecraftServer server, String rel, String content) {
        Path target = resolveSafe(server, rel);
        if (target == null) return Result.fail("Path escapes server directory");
        if (isOwnJar(target)) return Result.fail("Refusing to overwrite Almin's own jar");
        if (!isWritable(server, target)) {
            return Result.fail("Writes are limited to: " + AlminConfig.get().dirWritableRoots
                + " (or a world's datapacks/)");
        }
        if (Files.isDirectory(target)) return Result.fail("Path is a directory");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) return Result.fail("Content exceeds the write limit");
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".almin-web-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return Result.pass();
        } catch (IOException e) {
            return Result.fail("Write failed: " + e.getMessage());
        }
    }

    /** Deletes a file (not a non-empty directory) under a writable root. */
    public static Result delete(MinecraftServer server, String rel) {
        Path target = resolveSafe(server, rel);
        if (target == null) return Result.fail("Path escapes server directory");
        if (isOwnJar(target)) return Result.fail("Refusing to delete Almin's own jar");
        if (!isWritable(server, target)) {
            return Result.fail("Deletes are limited to: " + AlminConfig.get().dirWritableRoots
                + " (or a world's datapacks/)");
        }
        if (!Files.exists(target)) return Result.fail("No such file: " + rel);
        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> s = Files.list(target)) {
                    if (s.findAny().isPresent()) return Result.fail("Directory is not empty");
                }
            }
            Files.delete(target);
            return Result.pass();
        } catch (IOException e) {
            return Result.fail("Delete failed: " + e.getMessage());
        }
    }

    /**
     * Where a streamed upload may land, or the reason it may not.
     *
     * <p>Split out from {@link #write} because a binary upload is streamed to
     * disk rather than held in memory: the policy has to be settled before the
     * first byte is read, not after.
     */
    public record Target(Path path, String problem) {
        public boolean ok() { return path != null; }
    }

    /**
     * Cap on a streamed upload. Larger than {@link #MAX_WRITE_BYTES}, which
     * bounds a text field in a browser; this route exists for jars and packs.
     */
    public static final long MAX_UPLOAD_BYTES = 128L * 1024 * 1024;

    /** Applies the write rules to {@code rel} without touching its contents. */
    public static Target uploadTarget(MinecraftServer server, String rel) {
        Path target = resolveSafe(server, rel);
        if (target == null) return new Target(null, "Path escapes server directory");
        if (isOwnJar(target)) return new Target(null, "Refusing to overwrite Almin's own jar");
        if (!isWritable(server, target)) {
            return new Target(null, "Uploads are limited to: " + AlminConfig.get().dirWritableRoots
                + " (or a world's datapacks/)");
        }
        if (Files.isDirectory(target)) return new Target(null, "Path is a directory");
        return new Target(target, "");
    }

    /**
     * A file that may be streamed back to the browser, or null.
     *
     * <p>Reads are allowed anywhere under the server root — the same rule as
     * {@link #read}, minus its text-sized cap, since a download does not have
     * to fit in a textarea.
     */
    public static Path downloadable(MinecraftServer server, String rel) {
        Path target = resolveSafe(server, rel);
        if (target == null || !Files.isRegularFile(target)) return null;
        return target;
    }

    /** Renames a file within its directory, under a writable root. */
    public static Result rename(MinecraftServer server, String rel, String newName) {
        Path target = resolveSafe(server, rel);
        if (target == null) return Result.fail("Path escapes server directory");
        if (newName == null || newName.isBlank() || newName.contains("/") || newName.contains("\\")
                || newName.contains("..")) {
            return Result.fail("Invalid new name: " + newName);
        }
        if (isOwnJar(target)) return Result.fail("Refusing to rename Almin's own jar");
        if (!isWritable(server, target)) {
            return Result.fail("Renames are limited to: " + AlminConfig.get().dirWritableRoots
                + " (or a world's datapacks/)");
        }
        if (!Files.exists(target)) return Result.fail("No such file: " + rel);
        Path dest = target.resolveSibling(newName).toAbsolutePath().normalize();
        if (!dest.startsWith(root(server))) return Result.fail("New name resolves outside the server directory");
        if (Files.exists(dest)) return Result.fail("A file named " + newName + " already exists");
        try {
            Files.move(target, dest);
            return Result.pass();
        } catch (IOException e) {
            return Result.fail("Rename failed: " + e.getMessage());
        }
    }
}
