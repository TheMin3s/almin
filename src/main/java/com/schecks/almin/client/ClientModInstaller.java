package com.schecks.almin.client;

import com.schecks.almin.ModOfferPayload;
import com.schecks.almin.UpdateChecker;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

/**
 * Downloads mods a player has approved from a server's offer list.
 *
 * <p>Everything here runs only after an explicit Approve. The guards, in the
 * order they apply:
 * <ol>
 *   <li>the URL must be {@code https://} — a server cannot talk this client
 *       into a plaintext fetch;</li>
 *   <li>the response is capped at {@link #MAX_BYTES};</li>
 *   <li>if the offer carries a SHA-256, the download must match it exactly;</li>
 *   <li>the file must look like a real Fabric mod jar (it must contain a
 *       {@code fabric.mod.json}), so a server cannot drop an arbitrary file
 *       into the mods folder;</li>
 *   <li>only then is it moved into {@code mods/}, where it takes effect on the
 *       next launch. Nothing is loaded into the running game.</li>
 * </ol>
 *
 * <p>A failure on one mod does not stop the others; each is reported back.
 */
public final class ClientModInstaller {
    private static final Logger LOG = LoggerFactory.getLogger("almin");
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private ClientModInstaller() {}

    /** Outcome for one offered mod. */
    public record Outcome(String modId, boolean ok, String detail) {}

    /** True if this mod is already loaded, so it need not be offered again. */
    public static boolean alreadyInstalled(ModOfferPayload.Offer offer) {
        return !offer.modId().isBlank() && FabricLoader.getInstance().isModLoaded(offer.modId());
    }

    /** Downloads and installs every offer. Call off the render thread. */
    public static List<Outcome> installAll(List<ModOfferPayload.Offer> offers) {
        return offers.stream().map(ClientModInstaller::install).toList();
    }

    private static Outcome install(ModOfferPayload.Offer offer) {
        Path tmp = null;
        try {
            URI uri = new URI(offer.url());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return new Outcome(offer.modId(), false, "refused: not an https URL");
            }
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            Files.createDirectories(modsDir);
            tmp = Files.createTempFile(modsDir, ".almin-mod-", ".part");

            long size = download(uri, tmp);

            if (!offer.sha256().isBlank()) {
                String actual = sha256(tmp);
                if (!actual.equalsIgnoreCase(offer.sha256().trim())) {
                    Files.deleteIfExists(tmp);
                    LOG.warn("[Almin] {} rejected: sha256 mismatch (expected {}, got {})",
                        offer.modId(), offer.sha256(), actual);
                    return new Outcome(offer.modId(), false, "rejected: checksum did not match");
                }
            }
            if (!UpdateChecker.looksLikeValidMod(tmp)) {
                Files.deleteIfExists(tmp);
                return new Outcome(offer.modId(), false, "rejected: not a Fabric mod jar");
            }

            Path target = modsDir.resolve(safeFileName(offer));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            LOG.info("[Almin] installed {} ({} bytes) -> mods/{}", offer.modId(), size, target.getFileName());
            return new Outcome(offer.modId(), true, "installed");
        } catch (Exception e) {
            LOG.warn("[Almin] install of {} failed: {}", offer.modId(), e.toString());
            return new Outcome(offer.modId(), false, "failed: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * A filename derived only from the mod id and version — never from the URL,
     * so a crafted link can't choose a path or an extension.
     */
    private static String safeFileName(ModOfferPayload.Offer offer) {
        String id = offer.modId().replaceAll("[^A-Za-z0-9_.-]", "_");
        String ver = offer.version().replaceAll("[^A-Za-z0-9_.-]", "_");
        return ver.isBlank() ? id + ".jar" : id + "-" + ver + ".jar";
    }

    private static long download(URI uri, Path dest) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", "almin-mod")
            .GET()
            .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        long total = 0;
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("download exceeds " + MAX_BYTES + " bytes");
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }
}
