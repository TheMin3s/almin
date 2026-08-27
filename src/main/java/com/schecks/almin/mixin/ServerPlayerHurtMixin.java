package com.schecks.almin.mixin;

import com.schecks.almin.AlminUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels every damage source while a player is in their spawn-immunity
 * window (see {@link AlminUtil#applySpawnImmunity}). Hooks directly into
 * {@code ServerPlayer.hurtServer} because that's where damage actually goes
 * for players — {@code ServerPlayer} overrides {@code hurtServer} and the
 * super chain gets pre-empted before any Fabric ALLOW_DAMAGE listener on
 * {@code LivingEntity.hurtServer} would fire.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerHurtMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void almin$spawnImmunity(ServerLevel level, DamageSource source, float amount,
                                       CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (AlminUtil.isSpawnImmune(self.getUUID())) {
            cir.setReturnValue(false);
        }
    }
}
