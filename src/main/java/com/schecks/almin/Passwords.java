package com.schecks.almin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing for the web panel's admin login.
 *
 * PBKDF2-HMAC-SHA256 with a per-password random salt, stored as a single
 * self-describing string:
 *
 * <pre>pbkdf2_sha256$&lt;iterations&gt;$&lt;salt-b64&gt;$&lt;hash-b64&gt;</pre>
 *
 * The iteration count lives in the string, so it can be raised later without
 * invalidating existing hashes. There is no external dependency — this is all
 * JDK crypto. The plaintext password is never stored or logged; only the hash
 * above ever reaches disk.
 */
public final class Passwords {
    private static final String ALGO = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RNG = new SecureRandom();

    private Passwords() {}

    /** Hashes {@code password} with a fresh random salt. */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        byte[] dk = derive(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "pbkdf2_sha256$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
    }

    /**
     * Constant-time verification of {@code password} against a stored hash.
     * Returns false for a null/blank stored hash (no password set) or a
     * malformed one, never throwing.
     */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null || stored.isBlank()) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2_sha256")) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int bits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
            try {
                return SecretKeyFactory.getInstance(ALGO).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("PBKDF2 unavailable: " + e.getMessage(), e);
        }
    }
}
