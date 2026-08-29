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
            // Skip the loader's own scaffolding: every client has it and it is
            // never the answer to "what is different about this one".
            String id = meta.getId();
            if (id.equals("java") || id.startsWith("fabric-") && meta.getName().isEmpty()) continue;
            mods.add(ClientProfilePayload.clip(id + "@" + meta.getVersion().getFriendlyString()));
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
