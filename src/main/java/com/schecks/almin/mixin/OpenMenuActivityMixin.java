package com.schecks.almin.mixin;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.AlminConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Records a player opening a container — the chest-access half of the activity
 * log, and usually the reason someone goes looking at it.
 *
 * <p>Observes at HEAD and never cancels; a fault here cannot stop a container
 * from opening.
 */
@Mixin(ServerPlayer.class)
public class OpenMenuActivityMixin {

    @Inject(method = "openMenu", at = @At("HEAD"))
    private void almin$recordOpen(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        try {
            if (provider == null) return;
            if (!AlminConfig.get().activityItems) return;
            ActivityLog.record((ServerPlayer) (Object) this, "container",
                provider.getDisplayName().getString());
        } catch (Throwable ignored) {
            // Throwable, not RuntimeException: a pure observer must never be
            // the reason a container refuses to open.
        }
    }
}
