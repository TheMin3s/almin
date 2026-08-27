package com.schecks.almin.mixin;

import com.schecks.almin.NanoSupport;
import com.schecks.almin.TrustedOps;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When a player signs a writable book that was loaded by /almin op nano,
 * write the file back to disk before vanilla converts the book to a
 * written_book. Non-nano books are unaffected.
 *
 * Sign is detected by ServerboundEditBookPacket.title() being non-empty.
 * "Done" (no title) doesn't trigger a save — only Sign does.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class EditBookMixin {
    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void almin$saveOnSign(ServerboundEditBookPacket packet, CallbackInfo ci) {
        if (packet.title().isEmpty()) return;
        ServerPlayer self = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (self == null) return;

        int slot = packet.slot();
        Inventory inv = self.getInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) return;
        ItemStack stack = inv.getItem(slot);
        NanoSupport.NanoMarker marker = NanoSupport.readMarker(stack);
        if (marker == null) return;

        // Hard gate: only TrustedOps can trigger a file write via this path.
        // Without this check, ANY player who somehow obtained a nano book (drop,
        // chest transfer, /give with NBT from a vanilla op) could overwrite
        // arbitrary files under the server directory, including ops.json,
        // whitelist.json, mod jars, etc. The save() call itself also enforces
        // an issuer-UUID match — these two layers together mean a leaked book
        // is useless to anyone other than the trusted user who issued it.
        if (!TrustedOps.isTrusted(self.getUUID())) return;

        NanoSupport.Result result = NanoSupport.save(self, marker, slot, packet.pages());
        switch (result.kind) {
            case "ok" -> {
                self.sendSystemMessage(
                    Component.literal("Saved ").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                        .append(Component.literal(result.bytes + " chars on sign")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                        .append(Component.literal(" -> " + result.serverRoot.relativize(result.target))
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))));
                // Signing consumes the nano book(s): remove every part of this
                // file from the inventory, then skip vanilla's sign handling so
                // it doesn't try to convert an item we just deleted.
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    NanoSupport.NanoMarker m = NanoSupport.readMarker(inv.getItem(i));
                    if (m != null && m.path().equals(marker.path())
                            && marker.issuer() != null && marker.issuer().equals(m.issuer())) {
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                }
                self.inventoryMenu.broadcastChanges();
                ci.cancel();
            }
            case "denied" -> self.sendSystemMessage(
                Component.literal("Sign-save denied: this nano book was issued to a different account")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            case "missing" -> self.sendSystemMessage(
                Component.literal("Sign-save failed: missing book " + result.index + " of " + marker.total())
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            case "multi" -> self.sendSystemMessage(
                Component.literal("Sign-save failed: multiple copies of book " + result.index)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            case "escape" -> self.sendSystemMessage(
                Component.literal("Sign-save failed: path escapes server directory")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            case "io" -> self.sendSystemMessage(
                Component.literal("Sign-save failed: " + result.message)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
        }
    }
}
