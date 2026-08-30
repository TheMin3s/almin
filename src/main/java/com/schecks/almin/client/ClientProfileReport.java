package com.schecks.almin.client;

import com.schecks.almin.ClientProfilePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Telling the server what this client is running.
 *
 * <p>Sent once when the player joins, and only if the server has the Almin
 * server mod listening for it — {@code canSend} is false otherwise, so a
 * vanilla or unrelated server never receives anything.
 *
 * <h3>What it gathers</h3>
 * The mod list from the loader, the versions of Minecraft and the loader, the
 * launcher's own name for itself, and the shape of the machine: operating
 * system, version, architecture, processors, and the heap Java was given.
 *
 * <p>Each mod is reported as {@code id@version}, with {@code ^parent} on the
 * end for one that is bundled inside another jar rather than installed on
 * purpose. That one character is what lets the server show six mods somebody
 * chose instead of sixty they have never heard of.
 *
 * <p>And nothing else. No machine model, no serial, no username, no paths, no
 * addresses. Reading a Mac's model number would mean running {@code sysctl} on
 * the player's computer; a mod that shells out on someone's machine to report
 * what it found is a different kind of program, and this one does not do it.
 */
@Environment(EnvType.CLIENT)
public final class ClientProfileReport {

    private ClientProfileReport() {}

    public static void send() {
        try {
            if (!ClientPlayNetworking.canSend(ClientProfilePayload.TYPE)) return;
            ClientPlayNetworking.send(gather());
        } catch (Throwable t) {
            // A failure here must never be the reason a join goes wrong.
        }
    }

    static ClientProfilePayload gather() {
        FabricLoader loader = FabricLoader.getInstance();
        List<String> mods = new ArrayList<>();
        for (ModContainer mod : loader.getAllMods()) {
            var meta = mod.getMetadata();
            String id = meta.getId();
            if (id.equals("java")) continue;      // the runtime is not a mod
            String line = id + "@" + shorten(meta.getVersion().getFriendlyString());
            // Which mod ships this one inside its own jar, where there is one.
            // Fabric API is forty of these, and a list that does not say so
            // reads as forty things the player chose to install.
            String parent = mod.getContainingMod()
                .map(c -> c.getMetadata().getId()).orElse("");
            // The wire caps one field, and a mangled parent is worse than
            // none — a truncated id would group a mod under a mod that does
            // not exist.
            if (!parent.isEmpty()
                && line.length() + 1 + parent.length() <= ClientProfilePayload.MAX_FIELD) {
                line = line + "^" + parent;
            }
            mods.add(ClientProfilePayload.clip(line));
        }
        mods.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
        if (mods.size() > ClientProfilePayload.MAX_MODS) {
            mods = mods.subList(0, ClientProfilePayload.MAX_MODS);
        }

        return new ClientProfilePayload(
            ClientProfilePayload.clip(minecraftVersion()),
            ClientProfilePayload.clip("fabric " + versionOf(loader, "fabricloader")),
            ClientProfilePayload.clip(launcher()),
            ClientProfilePayload.clip(property("os.name")),
            ClientProfilePayload.clip(property("os.version")),
            ClientProfilePayload.clip(property("os.arch")),
            ClientProfilePayload.clip(property("java.version")),
            Runtime.getRuntime().availableProcessors(),
            (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024)),
            List.copyOf(mods));
    }

    /**
     * A version string short enough to leave room for the parent beside it.
     *
     * <p>Fabric versions carry a build hash — {@code 0.115.1+2e04a5f5d7} —
     * and the hash is never the thing anyone reads.
     */
    private static String shorten(String version) {
        if (version == null) return "";
        int plus = version.indexOf('+');
        String v = plus > 0 ? version.substring(0, plus) : version;
        return v.length() <= 24 ? v : v.substring(0, 24);
    }

    private static String minecraftVersion() {
        try {
            return SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String versionOf(FabricLoader loader, String id) {
        return loader.getModContainer(id)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("");
    }

    /**
     * What launched this. Vanilla and most third-party launchers set these,
     * and the ones that do not simply come back unknown.
     */
    private static String launcher() {
        String brand = property("minecraft.launcher.brand");
        String version = property("minecraft.launcher.version");
        if (brand.isEmpty()) return "unknown";
        return version.isEmpty() ? brand : brand + " " + version;
    }

    private static String property(String key) {
        try {
            String v = System.getProperty(key);
            return v == null ? "" : v;
        } catch (SecurityException e) {
            return "";
        }
    }
}
