package com.schecks.almin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    /** A jar's declared identity. {@code ok()} is false when it isn't a Fabric mod. */
    public record Meta(String modId, String name, String version) {
        public boolean ok() { return !modId.isEmpty(); }
        public static Meta none() { return new Meta("", "", ""); }
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
                return new Meta(id, name.isEmpty() ? id : name, str(o, "version"));
            }
        } catch (Exception e) {
            // A jar that cannot be read is simply one Almin knows nothing about.
            return Meta.none();
        }
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
