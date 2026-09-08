package com.schecks.almin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a Fabric jar's own account of itself.
 *
 * <h3>Why this matters</h3>
 * A client decides whether a player already has an advertised mod by asking
 * Fabric whether that <em>mod id</em> is loaded. The id is the one in the jar's
 * {@code fabric.mod.json} — not the name on the download page, not the
 * Modrinth slug, and not whatever the admin typed into the form. Get it wrong
 * and detection silently fails: the player is offered a mod they already have,
 * on every single join, and nothing anywhere says why.
 *
 * <p>So when Almin has the jar, it asks the jar. That is the only source that
 * cannot be wrong.
 */
public final class ModJars {
    /**
     * A jar's declared identity. {@code ok()} is false when it isn't a Fabric
     * mod. {@code icon} is the path of the jar's own icon <em>inside</em> the
     * jar, or "" — the panel uses it so a mod uploaded on a server with no way
     * out to the internet still has a picture next to its name.
     */
    public record Meta(String modId, String name, String version, String icon) {
        public Meta(String modId, String name, String version) {
            this(modId, name, version, "");
        }
        public boolean ok() { return !modId.isEmpty(); }
        public static Meta none() { return new Meta("", "", "", ""); }
    }

    /**
     * The rest of what a jar says about itself.
     *
     * <p>{@link Meta} is the part detection needs — the identity, and nothing
     * else. This is the part a person reads: what the mod is for, who wrote
     * it, where its page is, what it needs, and whether the server even has to
     * load it. Kept apart because one is asked of every jar on every join and
     * the other is asked once, when somebody opens a row.
     *
     * @param environment {@code "*"}, {@code "client"} or {@code "server"} as
     *                    the jar declares it — a client-only mod sitting in a
     *                    server's folder is a thing worth being told
     */
    public record Details(String description, List<String> authors, String environment,
                          String license, String homepage, String sources, String issues,
                          List<String> needs) {

        public static Details none() {
            return new Details("", List.of(), "", "", "", "", "", List.of());
        }
    }

    /** {@code fabric.mod.json} is small; anything this size is not one. */
    private static final int MAX_MANIFEST = 512 * 1024;

    private ModJars() {}

    /** What {@code jar} says it is, or {@link Meta#none()} if it says nothing. */
    public static Meta read(Path jar) {
        if (jar == null) return Meta.none();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return Meta.none();
            if (entry.getSize() > MAX_MANIFEST) return Meta.none();
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_MANIFEST);
                JsonObject o = JsonParser.parseString(
                    new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                String id = str(o, "id");
                if (id.isEmpty()) return Meta.none();
                String name = str(o, "name");
                return new Meta(id, name.isEmpty() ? id : name, str(o, "version"), iconPath(o));
            }
        } catch (Exception e) {
            // A jar that cannot be read is simply one Almin knows nothing about.
            return Meta.none();
        }
    }

    /**
     * The jar's icon path. Fabric allows either a plain string or an object
     * keyed by pixel size ({@code {"64": "assets/x/icon.png"}}); when it is the
     * latter, the largest size wins, because the panel scales down and never up.
     */
    private static String iconPath(JsonObject o) {
        try {
            if (!o.has("icon")) return "";
            JsonElement icon = o.get("icon");
            if (icon.isJsonPrimitive()) return icon.getAsString();
            if (!icon.isJsonObject()) return "";
            String best = "";
            int bestSize = -1;
            for (var e : icon.getAsJsonObject().entrySet()) {
                int size;
                try { size = Integer.parseInt(e.getKey()); } catch (NumberFormatException ex) { continue; }
                if (size > bestSize && e.getValue().isJsonPrimitive()) {
                    bestSize = size;
                    best = e.getValue().getAsString();
                }
            }
            return best;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** How large an icon this will pull out of a jar. */
    private static final int MAX_ICON = 1024 * 1024;

    /**
     * The bytes of {@code entry} inside {@code jar}, or null.
     *
     * <p>The entry name comes from the jar's own manifest, so it is not a
     * caller-supplied path — but a zip may still name an entry {@code ../..},
     * and this never touches the filesystem with it, only {@code ZipFile}.
     */
    public static byte[] entry(Path jar, String name) {
        if (jar == null || name == null || name.isBlank()) return null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.getSize() > MAX_ICON) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_ICON);
                return bytes.length == 0 ? null : bytes;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Everything else {@code fabric.mod.json} says, for one jar.
     *
     * <p>Every field is optional in the schema and plenty of jars leave most
     * of them out, so nothing here fails — a jar that says nothing comes back
     * saying nothing.
     */
    public static Details details(Path jar) {
        if (jar == null) return Details.none();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null || entry.getSize() > MAX_MANIFEST) return Details.none();
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_MANIFEST);
                JsonObject o = JsonParser.parseString(
                    new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject contact = o.has("contact") && o.get("contact").isJsonObject()
                    ? o.getAsJsonObject("contact") : new JsonObject();
                List<String> needs = new ArrayList<>();
                if (o.has("depends") && o.get("depends").isJsonObject()) {
                    for (String k : o.getAsJsonObject("depends").keySet()) {
                        // Everything depends on these three; saying so is noise.
                        if (k.equals("fabricloader") || k.equals("minecraft")
                            || k.equals("java")) continue;
                        needs.add(k);
                        if (needs.size() >= 16) break;
                    }
                }
                return new Details(cut(str(o, "description"), 600),
                    strings(o, "authors"),
                    cut(str(o, "environment"), 16), cut(str(o, "license"), 120),
                    cut(str(contact, "homepage"), 300), cut(str(contact, "sources"), 300),
                    cut(str(contact, "issues"), 300), needs);
            }
        } catch (Exception e) {
            return Details.none();
        }
    }

    /**
     * Manifest fields are whatever the jar's author typed. Every one of these
     * ends up on a page, so each has a length past which it is not telling
     * anybody anything they wanted to know.
     */
    private static String cut(String s, int max) {
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max).strip() + "…";
    }

    /**
     * A list of names, however the jar chose to write them.
     *
     * <p>The schema allows a plain string or an object with a {@code name} in
     * it, and real jars use both — sometimes in the same array.
     */
    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        try {
            if (!o.has(k) || !o.get(k).isJsonArray()) return out;
            for (var e : o.getAsJsonArray(k)) {
                if (e.isJsonPrimitive()) out.add(cut(e.getAsString(), 60));
                else if (e.isJsonObject()) {
                    String name = str(e.getAsJsonObject(), "name");
                    if (!name.isEmpty()) out.add(cut(name, 60));
                }
                if (out.size() >= 12) break;
            }
        } catch (RuntimeException ignored) {
            // A malformed list is a list nothing is known about.
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
