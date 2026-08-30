package com.schecks.almin.mixin;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.AlminConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records trades with a villager or a wandering trader.
 *
 * <p>The existing {@code interact} row says somebody right-clicked a villager,
 * which is also what walking past one and pressing the wrong button looks
 * like. This is the trade itself: the moment the bought item is taken out of
 * the merchant's result slot.
 *
 * <p>Worth separating because a trading hall is a distinct kind of afternoon,
 * and because "emptied every villager on the server" is a thing an admin
 * hears about and then has to go looking for.
 */
@Mixin(MerchantResultSlot.class)
public class TradeActivityMixin {

    @Inject(method = "onTake", at = @At("HEAD"))
    private void almin$recordTrade(Player player, ItemStack stack, CallbackInfo ci) {
        try {
            if (!AlminConfig.get().activityItems) return;
            if (stack == null || stack.isEmpty()) return;
            if (player instanceof ServerPlayer p) {
                ActivityLog.recordFolded(p, "trade", stack.getHoverName().getString());
            }
        } catch (Throwable ignored) {
            // Never the reason a trade fails.
        }
    }
}
