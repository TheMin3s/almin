package com.schecks.almin.mixin;

import com.schecks.almin.ActivityLog;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records what people write on signs.
 *
 * <p>A sign is placed as a blank block, so the placement row says "Oak Sign"
 * and nothing else — the text arrives afterwards in its own packet and was,
 * until now, the one thing a player could put into the world that the log
 * could not see. It is also the thing most often at the centre of a report:
 * signs are how people leave messages for each other, and how they leave
 * messages nobody wanted.
 *
 * <p>Recorded at the sign's own position rather than the player's, so the row
 * lands on the map where the sign is.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SignActivityMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleSignUpdate", at = @At("HEAD"))
    private void almin$recordSign(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        try {
            if (packet == null || player == null) return;
            String[] lines = packet.getLines();
            if (lines == null) return;
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                if (text.length() > 0) text.append(" / ");
                text.append(line.trim());
            }
            // A sign wiped blank is still an edit, and often the interesting one.
            ActivityLog.recordBlock(player, "sign",
                text.length() == 0 ? "(cleared)" : text.toString(), packet.getPos());
        } catch (Throwable ignored) {
            // Never the reason a sign refuses to save.
        }
    }
}
