package com.schecks.almin.mixin;

import com.schecks.almin.MaskConfig;
import com.schecks.almin.TrustedOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Two PlayerList hooks:
 *
 * <ol>
 *   <li>Hard-blocks any deop attempt targeting a TrustedOps UUID — vanilla
 *       /deop, /almin op remove, or any /almin op cmd /deop wrapper all funnel
 *       through PlayerList.deop(NameAndId), intercepted here at HEAD. The block
 *       is silent (deop() has no caller context; /almin op remove surfaces a
 *       friendly error in the common case).</li>
 *   <li>Lets player name lookups resolve a mask. getPlayerByName backs the
 *       bare-name path of EntityArgument (EntitySelector.findPlayers), so this
 *       makes /msg, /tp, /tell and every other name-targeting command accept a
 *       player's mask. Only kicks in when no real player matched, so real
 *       account names always win.</li>
 * </ol>
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "deop", at = @At("HEAD"), cancellable = true)
    private void almin$protectTrustedOps(NameAndId target, CallbackInfo ci) {
        if (target != null && TrustedOps.isTrusted(target.id())) {
            ci.cancel();
        }
    }

    @Inject(method = "getPlayerByName", at = @At("RETURN"), cancellable = true)
    private void almin$resolveMaskName(String name, CallbackInfoReturnable<ServerPlayer> cir) {
        if (cir.getReturnValue() != null) return;     // a real player matched — leave it
        UUID masked = MaskConfig.findByMask(name);
        if (masked == null) return;
        ServerPlayer p = ((PlayerList) (Object) this).getPlayer(masked);
        if (p != null) cir.setReturnValue(p);
    }
}
