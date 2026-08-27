package com.schecks.almin.mixin;

import com.mojang.authlib.GameProfile;
import com.schecks.almin.MaskConfig;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/**
 * Makes display-name masks visible on vanilla clients for the one path the
 * server-side {@code getDisplayName()} override can't reach: the name tag
 * rendered above a player's head. The client builds that from the player's
 * {@link GameProfile} name, which it learns from the ADD_PLAYER entry of this
 * packet — so we rewrite that profile's name to the mask on the way out.
 *
 * <p>Only the name changes; the UUID and the texture/skin properties are
 * copied verbatim, so skins still load. The tab-list entry keeps its own
 * {@code displayName} (set by {@link PlayerEntityMixin}); only the bare
 * profile name — which is all the nameplate has to go on — is masked here.
 *
 * <p>createPlayerInitializing (the join broadcast) routes through this same
 * (EnumSet, Collection) constructor, so this one hook covers it.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public abstract class PlayerInfoMaskMixin {
    @Shadow @Final @Mutable private List<Entry> entries;

    @Inject(method = "<init>(Ljava/util/EnumSet;Ljava/util/Collection;)V", at = @At("TAIL"))
    private void almin$maskProfileNames(EnumSet<Action> actions, Collection<ServerPlayer> players, CallbackInfo ci) {
        // The profile name is only consumed client-side when a player is added.
        if (!actions.contains(Action.ADD_PLAYER)) return;

        List<Entry> rewritten = new ArrayList<>(this.entries.size());
        boolean changed = false;
        for (Entry e : this.entries) {
            String mask = MaskConfig.maskFor(e.profileId());
            if (mask == null) {
                rewritten.add(e);
                continue;
            }
            GameProfile orig = e.profile();
            // Same UUID + properties (skin/cape), masked name.
            GameProfile masked = new GameProfile(orig.id(), mask, orig.properties());
            rewritten.add(new Entry(
                e.profileId(), masked, e.listed(), e.latency(), e.gameMode(),
                e.displayName(), e.showHat(), e.listOrder(), e.chatSession()));
            changed = true;
        }
        if (changed) this.entries = rewritten;
    }
}
