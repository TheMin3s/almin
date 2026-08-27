package com.schecks.almin.mixin;

import com.schecks.almin.MaskConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows a masked player's mask name in the tab list. Unmasked players fall
 * through to the vanilla name.
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void almin$injectListName(CallbackInfoReturnable<Component> cir) {
        ServerPlayer sp = (ServerPlayer)(Object) this;
        String mask = MaskConfig.maskFor(sp.getUUID());
        if (mask == null) return;                        // vanilla name
        MutableComponent nameText = Component.literal(mask)
            .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE));
        cir.setReturnValue(nameText);
    }
}
