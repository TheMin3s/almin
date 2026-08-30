package com.schecks.almin.mixin;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.AlminConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records enchanting.
 *
 * <p>Small, and one of the better markers of where a player's base actually
 * is: the enchanting table is somewhere they come back to, and a run of these
 * in one place says "this is home" more reliably than a bed does.
 */
@Mixin(ServerPlayer.class)
public class EnchantActivityMixin {

    @Inject(method = "onEnchantmentPerformed", at = @At("HEAD"))
    private void almin$recordEnchant(ItemStack stack, int levels, CallbackInfo ci) {
        try {
            if (!AlminConfig.get().activityProgress) return;
            if (stack == null || stack.isEmpty()) return;
            ActivityLog.record((ServerPlayer) (Object) this, "enchant",
                stack.getHoverName().getString() + "  " + levels + " levels");
        } catch (Throwable ignored) {
            // Never the reason an enchant fails.
        }
    }
}
