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
 * <p>{@link ActivityLog#record} does the filtering. By default nothing an op
 * or a trusted UUID types reaches the log at all; and an admin's {@code /almin}
 * commands are dropped even when admin tracking is turned on, because the log
 * is read through {@code /almin} and would otherwise fill with the act of
 * reading it.
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
        } catch (Throwable ignored) {
            // Throwable, not RuntimeException: this is a pure observer bolted
            // onto someone else's method, and nothing it does is worth taking
            // the server down for.
        }
    }
}
