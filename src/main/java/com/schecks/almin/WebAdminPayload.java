package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: what the web panel is currently doing, for the in-game
 * Web tab.
 *
 * <p>Carries no password and no hash — only whether one has been set. There is
 * nothing here a player could not already read off {@code /almin op web}.
 *
 * <p>{@link #lastError} is the reason the panel is not running, when there is
 * one. Almin's own log never reaches the server console by design, so without
 * this a panel that failed to bind looks exactly like a panel nobody turned on.
 *
 * <p>The codec is written out by hand rather than built with
 * {@code StreamCodec.composite}, which tops out at eight fields.
 */
public record WebAdminPayload(
        boolean running,
        boolean enabled,
        String bind,
        int port,
        int configuredPort,
        boolean passwordSet,
        boolean publicMetrics,
        boolean requireSecure,
        boolean supervisor,
        boolean startCommandSet,
        int sessionMinutes,
        String url,
        String lastError)
        implements CustomPacketPayload {

    private static final int MAX_BIND = 64;
    private static final int MAX_URL = 256;
    private static final int MAX_ERROR = 512;

    public static final CustomPacketPayload.Type<WebAdminPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:web_admin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WebAdminPayload> CODEC =
        StreamCodec.of(WebAdminPayload::write, WebAdminPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, WebAdminPayload p) {
        buf.writeBoolean(p.running);
        buf.writeBoolean(p.enabled);
        buf.writeUtf(clip(p.bind, MAX_BIND), MAX_BIND);
        buf.writeVarInt(p.port);
        buf.writeVarInt(p.configuredPort);
        buf.writeBoolean(p.passwordSet);
        buf.writeBoolean(p.publicMetrics);
        buf.writeBoolean(p.requireSecure);
        buf.writeBoolean(p.supervisor);
        buf.writeBoolean(p.startCommandSet);
        buf.writeVarInt(p.sessionMinutes);
        buf.writeUtf(clip(p.url, MAX_URL), MAX_URL);
        buf.writeUtf(clip(p.lastError, MAX_ERROR), MAX_ERROR);
    }

    private static WebAdminPayload read(RegistryFriendlyByteBuf buf) {
        return new WebAdminPayload(
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readUtf(MAX_BIND),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readVarInt(),
            buf.readUtf(MAX_URL),
            buf.readUtf(MAX_ERROR));
    }

    /**
     * Truncates rather than throwing. An over-long error string is a bad
     * message, not a reason to drop the packet and leave the tab blank.
     */
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public CustomPacketPayload.Type<WebAdminPayload> type() {
        return TYPE;
    }
}
