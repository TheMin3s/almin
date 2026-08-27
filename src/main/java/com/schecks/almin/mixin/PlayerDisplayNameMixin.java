package com.schecks.almin.mixin;

import com.schecks.almin.MaskConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides {@link Player#getDisplayName()} to return the configured mask name
 * when the player has one in {@link MaskConfig}. This catches most "show this
 * player's name" UI paths — above-head nameplate, death messages, advancement
 * broadcasts, leave/join messages — without touching the player's real
 * account, GameProfile, or chat sender.
 *
 * Server-side only via an {@code instanceof ServerPlayer} guard; on a physical
 * client the local {@link Player} doesn't know any masks anyway, so the
 * mixin is a no-op there.
 */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void almin$applyMask(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        if (!(self instanceof ServerPlayer)) return;
        String mask = MaskConfig.maskFor(self.getUUID());
        if (mask == null) return;
        cir.setReturnValue(Component.literal(mask));
    }
}
