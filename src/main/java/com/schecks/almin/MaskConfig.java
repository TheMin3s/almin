package com.schecks.almin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player display-name masks: real UUID -&gt; the name everyone sees in the
 * tab list, above the player's head, in chat, death messages, advancement
 * broadcasts, and most other "this player did X" UI lines.
 *
 * Manage live with {@code /almin mask set|clear|list}, or edit
 * {@code config/almin/masks.json} and run {@code /almin config reload}.
 *
 * <h3>How a mask reaches each surface</h3>
 * <ul>
 *   <li>Server-rendered text (chat sender, death/join/leave, advancements,
 *       {@code /msg} output) — {@code PlayerDisplayNameMixin} overrides
 *       {@code getDisplayName()}.</li>
 *   <li>Tab list — {@code PlayerEntityMixin} overrides
 *       {@code getTabListDisplayName()} (with the {@code [N❤]} lives prefix).</li>
 *   <li>Name tag above the head, on vanilla clients —
 *       {@code PlayerInfoMaskMixin} rewrites the ADD_PLAYER profile name.</li>
 *   <li>Command targeting ({@code /msg <mask>}, {@code /tp <mask>}, …) —
 *       {@code PlayerListMixin} adds a mask fallback to {@code getPlayerByName}.</li>
 * </ul>
 *
 * <h3>Scope</h3>
 * Masking is full and visible: a masked player can be impersonated in chat,
 * since the sender name follows the mask. Only the server console and
 * {@code almin.log} keep the real account, preserving the audit trail. A mask
 * may not equal another real player's name on this server — {@link #nameConflicts}
 * rejects that at set time and drops it at join (see {@link #onPlayerJoined}).
 */
public final class MaskConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, String> MASKS = new LinkedHashMap<>();
    private static volatile Path path;
    private static volatile MinecraftServer server;

    private MaskConfig() {}

    /** Load (or create) the masks file. Call once at server start. */
    public static synchronized void init(MinecraftServer mcServer) {
        server = mcServer;
        path = mcServer.getServerDirectory().resolve("config").resolve("almin").resolve("masks.json");
        if (!Files.exists(path)) {
            writeStub();
        }
        reload();
    }

    /** Re-read the file from disk. Returns true if loading succeeded. */
    public static synchronized boolean reload() {
        if (path == null) return false;
        MASKS.clear();
        if (!Files.exists(path)) return true;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (String key : obj.keySet()) {
                try {
                    UUID id = UUID.fromString(key);
                    String name = obj.get(key).getAsString();
                    if (name == null || name.isBlank()) continue;
                    if (nameConflicts(id, name)) {
                        AlminLog.warn("[almin] mask {} -> '{}' conflicts with a real player on this server; ignored",
                            id, name);
                        continue;
                    }
                    MASKS.put(id, name);
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entries (e.g. the _example stub)
                }
            }
            return true;
        } catch (Exception e) {
            AlminLog.warn("[almin] masks.json unreadable ({}), using empty masks", e.getMessage());
            return false;
        }
    }

    /**
     * True if applying mask {@code maskName} to {@code maskedId} would collide
     * with another player on this server — either currently online or known to
     * {@link PlayerHistory} from any past join. Self-mapping (masking to your own
     * real name) is allowed.
     */
    private static boolean nameConflicts(UUID maskedId, String maskName) {
        if (server == null) return false;
        ServerPlayer online = server.getPlayerList().getPlayerByName(maskName);
        if (online != null && !online.getUUID().equals(maskedId)) return true;
        UUID cached = PlayerHistory.get(server).findByName(maskName);
        return cached != null && !cached.equals(maskedId);
    }

    /**
     * Called when a player joins. If any active mask currently targets the
     * joining player's real account name, the mask is dropped — the real
     * account takes precedence so no one can impersonate the new arrival. The
     * masks.json file is left untouched; the next /almin config reload (or
     * server restart) will pick up the stored mapping again if the conflict is
     * gone.
     */
    public static synchronized void onPlayerJoined(UUID joiningId, String realName) {
        if (realName == null || realName.isBlank()) return;
        Iterator<Map.Entry<UUID, String>> it = MASKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, String> e = it.next();
            if (e.getKey().equals(joiningId)) continue;
            if (realName.equalsIgnoreCase(e.getValue())) {
                AlminLog.warn("[almin] mask {} -> '{}' dropped at runtime because real player {} ({}) just joined",
                    e.getKey(), e.getValue(), realName, joiningId);
                it.remove();
            }
        }
    }

    /** Returns the mask name for {@code id}, or null if the player is unmasked. */
    public static String maskFor(UUID id) {
        if (id == null) return null;
        return MASKS.get(id);
    }

    /**
     * Reverse lookup: the UUID whose mask equals {@code name} (case-insensitive),
     * or null if no mask matches. Lets commands resolve a masked player when an
     * admin/player types the mask name (e.g. {@code /msg <mask>}). Masks can
     * never equal a real player's name (see {@link #nameConflicts}), so this
     * never shadows a real account.
     */
    public static synchronized UUID findByMask(String name) {
        if (name == null || name.isBlank()) return null;
        for (Map.Entry<UUID, String> e : MASKS.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Outcome of {@link #setMask}. */
    public enum SetResult { OK, CONFLICT, INVALID, NOT_LOADED, IO_ERROR }

    /**
     * Sets (or replaces) the mask for {@code id} and writes masks.json. Applies
     * the same {@link #nameConflicts} guard as file loading, so a mask can't be
     * pointed at another real player's name. The change is live immediately;
     * callers should refresh tab-list display names afterwards.
     */
    public static synchronized SetResult setMask(UUID id, String name) {
        if (path == null) return SetResult.NOT_LOADED;
        if (id == null || name == null || name.isBlank()) return SetResult.INVALID;
        if (nameConflicts(id, name)) return SetResult.CONFLICT;
        MASKS.put(id, name);
        return persist() ? SetResult.OK : SetResult.IO_ERROR;
    }

    /**
     * Removes any mask on {@code id} and writes masks.json. Returns true if a
     * mask existed and was removed, false if the player wasn't masked.
     */
    public static synchronized boolean clearMask(UUID id) {
        if (path == null || id == null) return false;
        if (MASKS.remove(id) == null) return false;
        persist();
        return true;
    }

    /** A read-only copy of the current id -&gt; mask map, in insertion order. */
    public static synchronized Map<UUID, String> snapshot() {
        return new LinkedHashMap<>(MASKS);
    }

    /**
     * Writes the live map to masks.json (atomic temp-file swap). Like
     * {@link AlminConfig#save()}, this rewrites the file from in-memory state, so
     * any entries that were ignored at load time (conflicting names, the
     * {@code _example} stub) are not preserved on the next managed change.
     */
    private static boolean persist() {
        if (path == null) return false;
        try {
            Files.createDirectories(path.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, String> e : MASKS.entrySet()) {
                obj.addProperty(e.getKey().toString(), e.getValue());
            }
            Path tmp = Files.createTempFile(path.getParent(), ".masks-", ".tmp");
            Files.writeString(tmp, GSON.toJson(obj), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            AlminLog.warn("[almin] failed to write masks.json: {}", e.getMessage());
            return false;
        }
    }

    private static void writeStub() {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path,
                "{\n"
              + "  \"_example\": \"Replace this key with a player UUID and the value with the mask name; entries that aren't a valid UUID (like this one) are ignored.\"\n"
              + "}\n",
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            AlminLog.warn("[almin] failed to write masks.json stub: {}", e.getMessage());
        }
    }
}
