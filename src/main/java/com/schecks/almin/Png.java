package com.schecks.almin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes an ARGB raster out as a PNG, and reads one back.
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
 *
 * <p>{@link #decode} is the other direction, and exists for one reason: a
 * player head is a crop out of a skin, and a skin arrives as a PNG someone
 * else encoded. Reading is the harder half — the writer picks one shape and
 * sticks to it, while a reader must take whatever it is handed — so the
 * decoder covers every colour type and every row filter the format defines
 * at 8 bits per channel, and refuses anything else rather than guessing.
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

    /** A decoded image: ARGB pixels, row-major. */
    public record Image(int width, int height, int[] argb) {
        /** The pixel at (x, y), or fully transparent if it is outside. */
        public int at(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) return 0;
            return argb[y * width + x];
        }
    }

    /**
     * A ceiling on what will be decoded. Skins are 64x64 and mod icons are
     * rarely past 512x512; this leaves room for both without letting a
     * hostile header ask for a gigabyte of heap.
     */
    private static final int MAX_PIXELS = 2048 * 2048;

    /**
     * Reads a PNG into ARGB pixels.
     *
     * <p>Throws {@link IOException} for anything malformed, unsupported or
     * larger than {@link #MAX_PIXELS} — the caller's job is to fall back, not
     * to repair the image.
     */
    public static Image decode(byte[] data) throws IOException {
        if (data == null || data.length < 8) throw new IOException("not a PNG");
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (data[i] != SIGNATURE[i]) throw new IOException("not a PNG");
        }
        int width = 0, height = 0, depth = 0, colour = -1;
        byte[] palette = null, transparency = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();

        int at = 8;
        while (at + 8 <= data.length) {
            int length = readInt(data, at);
            if (length < 0 || at + 12 + length > data.length) throw new IOException("truncated PNG");
            String type = new String(data, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int body = at + 8;
            switch (type) {
                case "IHDR" -> {
                    if (length < 13) throw new IOException("short IHDR");
                    width = readInt(data, body);
                    height = readInt(data, body + 4);
                    depth = data[body + 8] & 0xFF;
                    colour = data[body + 9] & 0xFF;
                    // Interlaced PNGs arrive in seven passes with their own
                    // geometry. Nothing we read is ever interlaced, and half
                    // a decoder is worse than none.
                    if ((data[body + 12] & 0xFF) != 0) throw new IOException("interlaced PNG");
                    if (width <= 0 || height <= 0) throw new IOException("empty PNG");
                    if ((long) width * height > MAX_PIXELS) throw new IOException("PNG too large");
                    // 1, 2 and 4 bits are packed several samples to a byte,
                    // and Minecraft's own block textures use all of them —
                    // sand and dirt are 4-bit palettes, snow is 2-bit. 16 is
                    // legal PNG and used by nothing here.
                    if (depth != 1 && depth != 2 && depth != 4 && depth != 8) {
                        throw new IOException("PNG bit depth " + depth);
                    }
                }
                case "PLTE" -> palette = java.util.Arrays.copyOfRange(data, body, body + length);
                case "tRNS" -> transparency = java.util.Arrays.copyOfRange(data, body, body + length);
                case "IDAT" -> idat.write(data, body, length);
                case "IEND" -> at = data.length;   // stop; trailing bytes are not ours
                default -> { }                     // ancillary chunks are none of our business
            }
            if (at == data.length) break;
            at = body + length + 4;
        }
        if (colour < 0) throw new IOException("no IHDR");
        if (idat.size() == 0) throw new IOException("no image data");

        int channels = switch (colour) {
            case 0 -> 1;   // grey
            case 2 -> 3;   // rgb
            case 3 -> 1;   // palette index
            case 4 -> 2;   // grey + alpha
            case 6 -> 4;   // rgba
            default -> throw new IOException("colour type " + colour);
        };
        if (colour == 3 && palette == null) throw new IOException("palette PNG with no palette");
        if (depth != 8 && colour != 0 && colour != 3) {
            // Sub-byte depths are only defined for greyscale and palette.
            throw new IOException("bit depth " + depth + " with colour type " + colour);
        }

        // Bytes per row, rounded up: at four bits a sample, two samples share
        // a byte and an odd width leaves half of the last one unused.
        int stride = (width * channels * depth + 7) / 8;
        // What a filter means by "the pixel to the left". Below eight bits
        // that is one byte, because filtering happens on bytes and knows
        // nothing about how the samples inside them are packed.
        int bpp = Math.max(1, channels * depth / 8);

        byte[] raw = inflate(idat.toByteArray(), height * (1 + stride));
        if (raw.length < height * (long) (stride + 1)) throw new IOException("short image data");

        // Unfilter in place, row by row: every filter is defined against the
        // already-reconstructed bytes to the left and above.
        byte[] pixels = new byte[height * stride];
        for (int y = 0; y < height; y++) {
            int filter = raw[y * (stride + 1)] & 0xFF;
            int src = y * (stride + 1) + 1;
            int dst = y * stride;
            int up = dst - stride;
            for (int i = 0; i < stride; i++) {
                int x = raw[src + i] & 0xFF;
                int a = i >= bpp ? pixels[dst + i - bpp] & 0xFF : 0;
                int b = y > 0 ? pixels[up + i] & 0xFF : 0;
                int c = (y > 0 && i >= bpp) ? pixels[up + i - bpp] & 0xFF : 0;
                int value = switch (filter) {
                    case 0 -> x;
                    case 1 -> x + a;
                    case 2 -> x + b;
                    case 3 -> x + ((a + b) >> 1);
                    case 4 -> x + paeth(a, b, c);
                    default -> throw new IOException("row filter " + filter);
                };
                pixels[dst + i] = (byte) value;
            }
        }

        int[] argb = new int[width * height];
        if (depth != 8) {
            // One sample per pixel, packed high bits first, and greyscale
            // samples are scaled up to a full byte.
            int mask = (1 << depth) - 1;
            int max = mask;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int bit = x * depth;
                    int b = pixels[y * stride + (bit >> 3)] & 0xFF;
                    int sample = (b >> (8 - depth - (bit & 7))) & mask;
                    argb[y * width + x] = colour == 3
                        ? fromPalette(palette, transparency, sample)
                        : grey(sample * 255 / max, 255);
                }
            }
            return new Image(width, height, argb);
        }
        for (int i = 0, p = 0; i < argb.length; i++, p += channels) {
            argb[i] = switch (colour) {
                case 0 -> grey(pixels[p] & 0xFF, 255);
                case 2 -> 0xFF000000 | ((pixels[p] & 0xFF) << 16)
                        | ((pixels[p + 1] & 0xFF) << 8) | (pixels[p + 2] & 0xFF);
                case 3 -> fromPalette(palette, transparency, pixels[p] & 0xFF);
                case 4 -> grey(pixels[p] & 0xFF, pixels[p + 1] & 0xFF);
                default -> ((pixels[p + 3] & 0xFF) << 24) | ((pixels[p] & 0xFF) << 16)
                        | ((pixels[p + 1] & 0xFF) << 8) | (pixels[p + 2] & 0xFF);
            };
        }
        return new Image(width, height, argb);
    }

    private static int grey(int v, int alpha) {
        return (alpha << 24) | (v << 16) | (v << 8) | v;
    }

    private static int fromPalette(byte[] palette, byte[] transparency, int index) throws IOException {
        int off = index * 3;
        if (palette == null || off + 2 >= palette.length) throw new IOException("palette index out of range");
        int alpha = (transparency != null && index < transparency.length)
            ? transparency[index] & 0xFF : 255;
        return (alpha << 24) | ((palette[off] & 0xFF) << 16)
             | ((palette[off + 1] & 0xFF) << 8) | (palette[off + 2] & 0xFF);
    }

    /** The Paeth predictor: whichever neighbour the gradient a+b-c is closest to. */
    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    private static byte[] inflate(byte[] data, int expected) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, expected));
            byte[] buffer = new byte[16 * 1024];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    // Needing more input with none left means the stream was cut.
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                out.write(buffer, 0, n);
                if (out.size() > expected + 1024) break;   // more than the header promised
            }
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("bad zlib stream: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
             | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
