package com.schecks.almin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Safe, server-only staging and final installation of an Almin update jar. */
public final class ServerJarUpdate {
    public record Install(boolean ok, Path target, String message) {}

    private ServerJarUpdate() {}

    /** A non-jar name that Fabric and BlueMap will both ignore while it downloads. */
    public static Path stage(Path serverDir, String jarName) {
        String safe = safeJarName(jarName);
        return serverDir.resolve("mods").resolve(".almin-update-" + safe + ".part");
    }

    /**
     * Atomically installs a validated stage, keeping the running jar's pathname.
     *
     * <p>BlueMap scans Fabric mod jars on a background thread. Removing a
     * version-named Almin jar after BlueMap has listed it makes that scan fail
     * with {@code NoSuchFileException}. Replacing the contents at the same path
     * leaves every snapshot valid while still giving the next process the new
     * mod. The release filename is only a fallback when the running jar cannot
     * be located (for example, on a development classpath).
     */
    public static Install install(Path serverDir, Path staged, String jarName) {
        return install(serverDir, staged, jarName, UpdateChecker.ownJarPath());
    }

    /** Explicit running path kept separate so the filesystem behavior is testable offline. */
    static Install install(Path serverDir, Path staged, String jarName, Path runningJar) {
        String safe;
        try {
            safe = safeJarName(jarName);
        } catch (RuntimeException e) {
            return new Install(false, null, "invalid release jar name");
        }

        Path mods = serverDir.resolve("mods").toAbsolutePath().normalize();
        Path stage = staged == null ? null : staged.toAbsolutePath().normalize();
        if (stage == null || !mods.equals(stage.getParent())
                || !Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS)) {
            return new Install(false, null, "the staged update is missing or outside mods/");
        }

        Path releaseTarget = mods.resolve(safe);
        Path current = usableRunningJar(mods, runningJar);
        Path target = current == null ? releaseTarget : current;
        try {
            move(stage, target);
            if (current != null) {
                return new Install(true, target, "(replaced the running jar in place)");
            }
            return new Install(true, target, UpdateChecker.removeOldJar(target));
        } catch (IOException preferredFailed) {
            // A platform may lock the running jar. Preserve the old behavior as
            // a fallback so an update is not made impossible there.
            if (!target.equals(releaseTarget)) {
                try {
                    move(stage, releaseTarget);
                    return new Install(true, releaseTarget,
                        UpdateChecker.removeOldJar(releaseTarget));
                } catch (IOException fallbackFailed) {
                    return new Install(false, null, fallbackFailed.getMessage());
                }
            }
            return new Install(false, null, preferredFailed.getMessage());
        }
    }

    public static void discard(Path staged) {
        if (staged == null) return;
        try { Files.deleteIfExists(staged); }
        catch (IOException ignored) {}
    }

    private static Path usableRunningJar(Path mods, Path candidate) {
        if (candidate == null) return null;
        Path current = candidate.toAbsolutePath().normalize();
        if (!mods.equals(current.getParent()) || current.getFileName() == null
                || !current.getFileName().toString().endsWith(".jar")
                || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) return null;
        return current;
    }

    private static String safeJarName(String jarName) {
        Path named = Path.of(jarName == null ? "" : jarName);
        String safe = named.getFileName() == null ? "" : named.getFileName().toString();
        if (!safe.equals(jarName) || !safe.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("unsafe asset name");
        }
        return safe;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomic) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ordinary) {
                ordinary.addSuppressed(atomic);
                throw ordinary;
            }
        }
    }
}
