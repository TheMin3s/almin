package com.schecks.almin.mixin;

import com.schecks.almin.events.ActivityHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records a block actually being placed.
 *
 * <h3>Why a mixin</h3>
 * Fabric has no placement event. {@code UseBlockCallback} fires <em>before</em>
 * the interaction resolves, when putting a block down and opening a chest still
 * look identical — which is why placing dirt used to be logged as "used Dirt on
 * Dirt". Placement is decided here, inside {@code BlockItem}, and this is the
 * first moment anyone can tell the two apart.
 *
 * <p>At RETURN, and only when the result says the placement took: a refused
 * placement is not one. Never cancels, so a fault here cannot stop a block
 * being placed — the worst it can do is fail to write the row.
 *
 * @see ActivityHooks#placed
 */
@Mixin(BlockItem.class)
public class BlockPlaceActivityMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void almin$recordPlace(BlockPlaceContext context,
                                   CallbackInfoReturnable<InteractionResult> cir) {
        try {
            InteractionResult result = cir.getReturnValue();
            if (result == null || !result.consumesAction()) return;
            Level level = context.getLevel();
            if (level == null || level.isClientSide()) return;
            // Dispensers and other block sources place without a player.
            if (!(context.getPlayer() instanceof ServerPlayer player)) return;
            BlockPos pos = context.getClickedPos();
            ActivityHooks.placed(player, level.getBlockState(pos), pos);
        } catch (Throwable ignored) {
            // Throwable, not RuntimeException: an observer must never be the
            // reason a block refuses to go down.
        }
    }
}
