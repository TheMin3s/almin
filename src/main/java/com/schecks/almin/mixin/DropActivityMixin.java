package com.schecks.almin.mixin;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.AlminConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records items thrown on the ground.
 *
 * <p>Dropping is usually nothing. It is occasionally the whole story: a chest
 * emptied onto the floor, a stack handed to somebody standing next to you, or
 * everything a player owned dropped in one place a minute before they left.
 * None of that appears anywhere else in the log.
 *
 * <p>Folded hard, because emptying an inventory is one of these per stack.
 */
@Mixin(ServerPlayer.class)
public class DropActivityMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"))
    private void almin$recordDrop(ItemStack stack, boolean dropAround, boolean traceItem,
                                  CallbackInfoReturnable<ItemEntity> cir) {
        try {
            if (!AlminConfig.get().activityItems) return;
            if (stack == null || stack.isEmpty()) return;
            String what = stack.getCount() > 1
                ? stack.getCount() + "× " + stack.getHoverName().getString()
                : stack.getHoverName().getString();
            ActivityLog.recordFolded((ServerPlayer) (Object) this, "drop", what);
        } catch (Throwable ignored) {
            // Never the reason a drop fails.
        }
    }
}
