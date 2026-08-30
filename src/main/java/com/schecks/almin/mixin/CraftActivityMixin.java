package com.schecks.almin.mixin;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.AlminConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records what players craft.
 *
 * <p>There is no crafting event in Fabric, and the honest hook is the moment
 * the result actually leaves the grid — before that, a recipe showing in the
 * output square has not been made. {@code ResultSlot#onTake} is that moment,
 * and it is the same one the game uses to award the recipe.
 *
 * <p>Folded, because shift-clicking a stack of sticks is sixteen of these.
 * The count on the row is how many times, not how many items, which is the
 * number a person reading it can act on.
 */
@Mixin(ResultSlot.class)
public class CraftActivityMixin {

    @Inject(method = "onTake", at = @At("HEAD"))
    private void almin$recordCraft(Player player, ItemStack stack, CallbackInfo ci) {
        try {
            if (!AlminConfig.get().activityItems) return;
            if (stack == null || stack.isEmpty()) return;
            if (player instanceof ServerPlayer p) {
                ActivityLog.recordFolded(p, "craft", stack.getHoverName().getString());
            }
        } catch (Throwable ignored) {
            // A pure observer must never be the reason a craft fails.
        }
    }
}
