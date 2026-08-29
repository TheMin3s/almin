package com.schecks.almin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes an ARGB raster out as a PNG.
 *
 * <h3>Why not ImageIO</h3>
 * {@code javax.imageio} would do this in three lines, but it lives in
 * {@code java.desktop} — a module a trimmed server JRE is entitled not to
 * ship, and the failure would be a {@code NoClassDefFoundError} at the moment
 * someone opened the map rather than anything catchable at start. PNG's own
 * format is small enough to write out honestly: a signature, three chunks, and
 * a zlib stream. Nothing here is clever; it is just spelled out.
 *
 * <p>Colour type 6 (RGBA, 8 bits) with filter 0 on every row. Real encoders
 * pick a filter per row to help compression; terrain images are large flat
 * areas of one colour, which deflate handles well enough on its own that the
 * extra machinery would not earn its place.
 */
public final class Png {
    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    private Png() {}

    /**
     * Encodes {@code pixels} (ARGB, row-major, {@code width * height} long).
     *
     * <p>Fully transparent pixels stay transparent, which is how a region the
     * server has not loaded reads as "not known" rather than as black ground
     * that isn't there.
     */
    public static byte[] encode(int[] pixels, int width, int height) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("empty image");
        if (pixels.length < width * height) throw new IOException("short pixel array");

        ByteArrayOutputStream out = new ByteArrayOutputStream(width * height + 1024);
        out.write(SIGNATURE);

        // IHDR: width, height, bit depth, colour type, then three zeroes for
        // compression, filter and interlace — all of which have one legal value.
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;            // bits per channel
        ihdr[9] = 6;            // truecolour with alpha
        chunk(out, "IHDR", ihdr);

        // Each row is prefixed with its filter type. 0 means "store as-is".
        byte[] raw = new byte[height * (1 + width * 4)];
        int at = 0;
        for (int y = 0; y < height; y++) {
            raw[at++] = 0;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int argb = pixels[row + x];
                raw[at++] = (byte) (argb >> 16);
                raw[at++] = (byte) (argb >> 8);
                raw[at++] = (byte) argb;
                raw[at++] = (byte) (argb >>> 24);
            }
        }
        chunk(out, "IDAT", deflate(raw));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4 + 64);
            byte[] buffer = new byte[16 * 1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                if (n == 0 && deflater.needsInput()) break;
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] body)
            throws IOException {
        byte[] length = new byte[4];
        putInt(length, 0, body.length);
        out.write(length);

        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(name);
        out.write(body);

        // The CRC covers the type and the body, but not the length.
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        byte[] check = new byte[4];
        putInt(check, 0, (int) crc.getValue());
        out.write(check);
    }

    private static void putInt(byte[] target, int at, int value) {
        target[at]     = (byte) (value >>> 24);
        target[at + 1] = (byte) (value >>> 16);
        target[at + 2] = (byte) (value >>> 8);
        target[at + 3] = (byte) value;
    }
}
