package com.schecks.almin;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client -&gt; server: what this player is running.
 *
 * <p>Sent once, at join, by the Almin client mod. It answers the question an
 * admin actually has when a player says "it crashed" or when something on the
 * server looks wrong: which mods are on that client, and what is it running on.
 *
 * <h3>What is in it, and what is deliberately not</h3>
 * The mod list — each entry {@code id@version}, with {@code ^parent} appended
 * when the loader says that mod is bundled inside another — the Minecraft and
 * loader versions, the launcher's own name for
 * itself, and the shape of the machine — operating system, version,
 * architecture, processor count, how much memory Java was given. That is what
 * a support question needs.
 *
 * <p>It does not carry a machine model, a serial number, a username, a file
 * path, a network address or anything else that identifies the computer rather
 * than describing it. Reading a Mac's model would mean running {@code sysctl}
 * on somebody's machine, and a mod that shells out on a player's computer to
 * report what it found is a different kind of program from this one.
 *
 * <h3>Self-reported</h3>
 * Every field comes from the client and a modified client can put anything it
 * likes in them. This is a support tool and a house rule, not an anti-cheat:
 * anything that treats it as proof is wrong about what it has.
 */
public record ClientProfilePayload(String minecraft, String loader, String launcher,
                                   String os, String osVersion, String arch,
                                   String java, int cores, int memoryMb,
                                   List<String> mods) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientProfilePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("almin:client_profile"));

    /** Ceiling on one field, so a crafted client cannot send an essay. */
    public static final int MAX_FIELD = 64;

    /** Ceiling on the list. A very modded client is a few hundred. */
    public static final int MAX_MODS = 600;

    /** Ceiling on the whole packet, for the registration that carries it. */
    public static final int MAX_BYTES = 64 * 1024;

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientProfilePayload> CODEC =
        StreamCodec.of(
            (buf, value) -> {
                writeText(buf, value.minecraft());
                writeText(buf, value.loader());
                writeText(buf, value.launcher());
                writeText(buf, value.os());
                writeText(buf, value.osVersion());
                writeText(buf, value.arch());
                writeText(buf, value.java());
                buf.writeVarInt(value.cores());
                buf.writeVarInt(value.memoryMb());
                List<String> mods = value.mods();
                int n = Math.min(mods.size(), MAX_MODS);
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) writeText(buf, mods.get(i));
            },
            buf -> {
                String minecraft = readText(buf);
                String loader = readText(buf);
                String launcher = readText(buf);
                String os = readText(buf);
                String osVersion = readText(buf);
                String arch = readText(buf);
                String java = readText(buf);
                int cores = buf.readVarInt();
                int memory = buf.readVarInt();
                int n = Math.min(Math.max(0, buf.readVarInt()), MAX_MODS);
                List<String> mods = new java.util.ArrayList<>(n);
                for (int i = 0; i < n; i++) mods.add(readText(buf));
                return new ClientProfilePayload(minecraft, loader, launcher, os, osVersion,
                    arch, java, Math.max(0, cores), Math.max(0, memory), List.copyOf(mods));
            });

    private static void writeText(RegistryFriendlyByteBuf buf, String s) {
        ByteBufCodecs.stringUtf8(MAX_FIELD).encode(buf, clip(s));
    }

    private static String readText(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.stringUtf8(MAX_FIELD).decode(buf);
    }

    /** Trimmed to what the codec will carry, so a long value is not a failed join. */
    public static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= MAX_FIELD ? t : t.substring(0, MAX_FIELD);
    }

    @Override
    public CustomPacketPayload.Type<ClientProfilePayload> type() {
        return TYPE;
    }
}
