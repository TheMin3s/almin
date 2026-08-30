package com.schecks.almin.mixin;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.AlminConfig;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records advancements as they are earned.
 *
 * <p>The one row in the log that is a milestone rather than an act. It is
 * cheap to keep and it is what makes a session readable weeks later: "entered
 * the Nether" at 19:12 explains the two hours of tunnelling that follow it far
 * better than the tunnelling does.
 *
 * <p>Only advancements with a display are recorded. Every advancement in the
 * game is built out of hidden criteria holders, and recording those would
 * write a dozen rows for one popup.
 *
 * <p>{@code award} returns false when the advancement was already held, so
 * this observes the return rather than the head — a re-award is not an event.
 */
@Mixin(PlayerAdvancements.class)
public class AdvancementActivityMixin {

    @Shadow private ServerPlayer player;

    @Inject(method = "award", at = @At("RETURN"))
    private void almin$recordAward(AdvancementHolder holder, String criterion,
                                   CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
            if (!AlminConfig.get().activityProgress) return;
            if (holder == null || player == null) return;
            Advancement advancement = holder.value();
            if (advancement == null || advancement.display().isEmpty()) return;
            ActivityLog.record(player, "advancement", Advancement.name(holder).getString());
        } catch (Throwable ignored) {
            // Never the reason an advancement fails to be granted.
        }
    }
}
