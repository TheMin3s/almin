package com.schecks.almin.mixin;

import com.mojang.brigadier.ParseResults;
import com.schecks.almin.ActivityLog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the commands ordinary players run.
 *
 * <p>Fabric has no command-execution event, so this rides the dispatcher.
 * It observes at HEAD and never cancels: a fault here cannot stop a command
 * from running, only from being written down.
 *
 * <p>{@link ActivityLog#record} does the filtering, so nothing an op or a
 * trusted UUID types reaches the log — including their own {@code /almin}
 * commands.
 */
@Mixin(Commands.class)
public class CommandActivityMixin {

    @Inject(method = "performCommand", at = @At("HEAD"))
    private void almin$recordCommand(ParseResults<CommandSourceStack> parse, String command,
                                     CallbackInfo ci) {
        try {
            CommandSourceStack source = parse.getContext().getSource();
            if (source.getEntity() instanceof ServerPlayer p) {
                ActivityLog.record(p, "command", command);
            }
        } catch (RuntimeException ignored) {
            // Never let logging break a command.
        }
    }
}
