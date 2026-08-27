package com.schecks.almin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WritableBookContent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * Marker + save logic for /almin op nano. Shared between the AlminCommand
 * /almin op nano save path and the EditBookMixin sign-on-save path.
 */
public final class NanoSupport {
    /**
     * issuer is the UUID of the trusted player who originally ran
     * /almin op nano to load this file. Only that exact UUID can save it
     * back, even if another trusted user picks the book up.
     */
    public record NanoMarker(String path, int book, int total, UUID issuer) {}

    /**
     * Save outcomes. Encoded as a short string so callers in different
     * contexts (command vs mixin) can format messages appropriately.
     */
    public static final class Result {
        public final String kind;       // "ok" / "missing" / "multi" / "escape" / "io" / "noserver" / "denied"
        public final int index;         // for missing/multi
        public final long bytes;        // for ok
        public final String message;    // for io
        public final Path target;       // for ok
        public final Path serverRoot;   // for ok

        private Result(String kind, int index, long bytes, String message, Path target, Path serverRoot) {
            this.kind = kind;
            this.index = index;
            this.bytes = bytes;
            this.message = message;
            this.target = target;
            this.serverRoot = serverRoot;
        }
        public static Result ok(long bytes, Path target, Path root) { return new Result("ok", 0, bytes, null, target, root); }
        public static Result missing(int idx) { return new Result("missing", idx, 0, null, null, null); }
        public static Result multi(int idx) { return new Result("multi", idx, 0, null, null, null); }
        public static Result escape() { return new Result("escape", 0, 0, null, null, null); }
        public static Result io(String msg) { return new Result("io", 0, 0, msg, null, null); }
        public static Result noServer() { return new Result("noserver", 0, 0, null, null, null); }
        public static Result denied() { return new Result("denied", 0, 0, null, null, null); }
    }

    private NanoSupport() {}

    public static NanoMarker readMarker(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.WRITABLE_BOOK)) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains("almin_nano")) return null;
        CompoundTag nano = tag.getCompoundOrEmpty("almin_nano");
        String path = nano.getStringOr("path", "");
        if (path.isEmpty()) return null;
        UUID issuer = null;
        String issuerStr = nano.getStringOr("issuer", "");
        if (!issuerStr.isEmpty()) {
            try { issuer = UUID.fromString(issuerStr); }
            catch (IllegalArgumentException ignored) { /* malformed marker, leave null */ }
        }
        return new NanoMarker(path, nano.getIntOr("book", 0), nano.getIntOr("total", 1), issuer);
    }

    /**
     * Gather every nano book in the player's inventory matching {@code marker.path()}
     * and write the concatenated contents back to disk.
     *
     * If {@code overrideSlot >= 0}, the book at that inventory slot has its stored
     * pages replaced by {@code overridePages} before assembly — used by the sign
     * hook where the latest edits haven't been written into the inventory item yet.
     *
     * Authorisation (two layers, both must pass):
     *   1. Caller must be in {@link TrustedOps}. Protects against forged or
     *      leaked nano books reaching this code from a non-trusted player.
     *   2. Caller's UUID must match {@code marker.issuer()}. Means a trusted
     *      user can't save another trusted user's loaded book — each editing
     *      session is bound to the player who started it.
     * When assembling multi-book files, only parts with a matching issuer
     * are accepted; parts from a different trusted user or with no issuer
     * are skipped (and surface as "missing" if needed).
     */
    public static Result save(ServerPlayer self, NanoMarker marker, int overrideSlot, List<String> overridePages) {
        MinecraftServer server = self.level().getServer();
        if (server == null) return Result.noServer();

        UUID caller = self.getUUID();
        if (!TrustedOps.isTrusted(caller)) {
            return Result.denied();
        }
        if (marker.issuer() == null || !marker.issuer().equals(caller)) {
            return Result.denied();
        }

        ItemStack[] parts = new ItemStack[marker.total()];
        int[] slots = new int[marker.total()];
        for (int i = 0; i < slots.length; i++) slots[i] = -1;

        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            NanoMarker m = readMarker(s);
            if (m == null) continue;
            if (!m.path().equals(marker.path())) continue;
            if (m.total() != marker.total()) continue;
            // Only accept parts issued to the same caller; rejects forged
            // parts, parts with no issuer, or parts from another trusted user.
            if (m.issuer() == null || !m.issuer().equals(caller)) continue;
            int idx = m.book();
            if (idx < 0 || idx >= marker.total()) continue;
            if (parts[idx] != null) return Result.multi(idx + 1);
            parts[idx] = s;
            slots[idx] = i;
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < marker.total(); i++) {
            if (parts[i] == null) return Result.missing(i + 1);
            if (slots[i] == overrideSlot && overridePages != null) {
                for (String p : overridePages) out.append(p);
            } else {
                WritableBookContent content = parts[i].get(DataComponents.WRITABLE_BOOK_CONTENT);
                if (content == null) continue;
                for (Filterable<String> page : content.pages()) {
                    out.append(page.raw());
                }
            }
        }

        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        Path target = Path.of(marker.path()).toAbsolutePath().normalize();
        if (!target.startsWith(root)) return Result.escape();

        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".almin-nano-", ".tmp");
            Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            return Result.io(e.getMessage());
        }
        return Result.ok(out.length(), target, root);
    }

    /**
     * Save path for the modded-client nano editor (NanoSavePayload). A modded
     * client can forge that packet, so this re-checks two things itself:
     *   1. the sender must be in {@link TrustedOps};
     *   2. the resolved target must stay under the server directory.
     * Neither is taken on trust from the packet.
     */
    public static Result saveFromEditor(MinecraftServer server, ServerPlayer self,
                                        String pathStr, String content) {
        if (server == null) return Result.noServer();
        if (!TrustedOps.isTrusted(self.getUUID())) return Result.denied();
        if (content.length() > NanoOpenPayload.MAX_CHARS) {
            return Result.io("file too large (" + content.length() + " chars)");
        }

        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(pathStr).toAbsolutePath().normalize();
        if (!target.startsWith(root)) return Result.escape();

        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, ".almin-nano-", ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            return Result.io(e.getMessage());
        }
        return Result.ok(content.length(), target, root);
    }
}
