package com.schecks.almin;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Minecraft's own {@code server.properties}, edited from the panel.
 *
 * <p>Everything else Almin exposes is Almin's. This one is not: it is the
 * file the game reads at boot, and it is the file an admin actually wants
 * when they want to change the view distance or turn the whitelist on. Making
 * them SSH in for that while the panel offers a file browser they could edit
 * it in anyway is a worse answer than doing it properly.
 *
 * <h3>Written by lines, not by Properties</h3>
 * {@link Properties#store} would work and would throw away every comment in
 * the file, reorder it, and stamp a date on top. Minecraft's own file is
 * mostly comments and the order is the order people expect, so writes replace
 * the value on the line the key is already on and leave everything else
 * exactly as it was. A key that is not in the file yet is appended.
 *
 * <h3>What it will not show</h3>
 * {@code rcon.password} and anything else whose name says password comes back
 * masked, and sending the mask back leaves it alone. The panel is behind a
 * login, but a credential that renders into a page is a credential in a
 * screenshot, and there is no reason for this one ever to be on screen.
 *
 * <h3>When it takes effect</h3>
 * At the next restart, for almost everything: the server reads this file when
 * it boots and keeps its own copy. Saying so is the panel's job, and it does.
 */
public final class ServerProperties {

    /** One setting, as the panel needs it. */
    public record Entry(String key, String value, String type, boolean secret) {}

    /** Values whose name says they should not be on screen. */
    private static boolean secret(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("secret") || k.contains("token");
    }

    /** Stands in for a secret, and means "leave it alone" on the way back. */
    public static final String MASK = "••••••••";

    /** Ceiling on the file, so a mistaken path cannot be read into memory. */
    private static final long MAX_BYTES = 512 * 1024;

    /** Ceiling on one value, so the panel cannot be used to write a payload. */
    private static final int MAX_VALUE = 2048;

    private ServerProperties() {}

    public static Path fileFor(MinecraftServer server) {
        return server.getServerDirectory().toAbsolutePath().normalize()
            .resolve("server.properties");
    }

    /** Everything in the file, in the order it appears in it. */
    public static List<Entry> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("No server.properties here");
        if (Files.size(file) > MAX_BYTES) throw new IOException("server.properties is too large");

        // Parsed properly for the values — escapes and all — and read again as
        // lines for the order, because Properties is a hash map and the file
        // is a document.
        Properties parsed = new Properties();
        try (var in = Files.newInputStream(file)) {
            parsed.load(in);
        }
        List<Entry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String key = keyOf(line);
            if (key == null || !seen.add(key)) continue;
            String value = parsed.getProperty(key);
            if (value == null) continue;
            out.add(entry(key, value));
        }
        // Anything Properties found that the line scan did not — a continued
        // line, or a separator this does not handle. Better listed than lost.
        for (String key : parsed.stringPropertyNames()) {
            if (seen.add(key)) out.add(entry(key, parsed.getProperty(key)));
        }
        return out;
    }

    private static Entry entry(String key, String value) {
        boolean hidden = secret(key);
        return new Entry(key, hidden ? (value.isEmpty() ? "" : MASK) : value,
            typeOf(value), hidden);
    }

    /**
     * What kind of control the panel should draw, guessed from the value.
     *
     * <p>There is no schema for this file — it is whatever the game and the
     * mods on it decided to write — so the value is the only evidence there
     * is. Guessing wrong costs a text box, which is what everything would have
     * been anyway.
     */
    static String typeOf(String value) {
        if (value.equals("true") || value.equals("false")) return "BOOL";
        if (value.matches("-?\\d{1,9}")) return "INT";
        return "TEXT";
    }

    /** The key on a properties line, or null if the line has none. */
    static String keyOf(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return null;
        int cut = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '=' || c == ':') { cut = i; break; }
        }
        if (cut < 0) return null;
        return unescape(t.substring(0, cut)).strip();
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { b.append(s.charAt(++i)); continue; }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Applies changes, keeping the file's own shape.
     *
     * @param changes key to new value; a secret whose value is {@link #MASK}
     *                is left as it was
     * @return how many values actually changed
     */
    public static int write(Path file, Map<String, String> changes) throws IOException {
        List<Entry> before = read(file);
        Map<String, Entry> was = new LinkedHashMap<>();
        for (Entry e : before) was.put(e.key(), e);

        Map<String, String> apply = new LinkedHashMap<>();
        for (Map.Entry<String, String> c : changes.entrySet()) {
            String key = c.getKey();
            String value = c.getValue();
            if (key == null || value == null) continue;
            if (!key.matches("[A-Za-z0-9._\\-]{1,64}")) {
                throw new IOException("Not a settings name: " + key);
            }
            if (value.length() > MAX_VALUE) throw new IOException("Value too long for " + key);
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IOException("A value cannot span lines: " + key);
            }
            Entry old = was.get(key);
            // The mask coming back means nobody typed a new one.
            if (old != null && old.secret() && value.equals(MASK)) continue;
            apply.put(key, value);
        }
        if (apply.isEmpty()) return 0;

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(lines.size() + apply.size());
        java.util.Set<String> done = new java.util.HashSet<>();
        int changed = 0;
        for (String line : lines) {
            String key = keyOf(line);
            if (key == null || !apply.containsKey(key) || !done.add(key)) {
                out.add(line);
                continue;
            }
            String value = apply.get(key);
            out.add(key + "=" + escape(value));
            changed++;
        }
        for (Map.Entry<String, String> e : apply.entrySet()) {
            if (done.contains(e.getKey())) continue;
            out.add(e.getKey() + "=" + escape(e.getValue()));
            changed++;
        }

        Path tmp = Files.createTempFile(file.getParent(), ".server-properties-", ".tmp");
        Files.write(tmp, out, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        return changed;
    }

    /**
     * Escapes a value the way a properties file needs.
     *
     * <p>Only what actually matters on the value side: a backslash, a leading
     * space, and the characters that would end the line. {@code =} and
     * {@code :} do not need escaping after the separator, and escaping them
     * anyway makes a file people read by hand harder to read.
     */
    static String escape(String value) {
        StringBuilder b = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\t' -> b.append("\\t");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case ' ' -> b.append(i == 0 ? "\\ " : " ");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
