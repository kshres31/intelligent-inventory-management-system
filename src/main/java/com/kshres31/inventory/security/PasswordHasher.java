package com.kshres31.inventory.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 password hashing from the JDK, stored as
 * {@code pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>} so the work factor can be
 * raised later without breaking existing hashes.
 */
public final class PasswordHasher {
    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher(int iterations) {
        if (iterations < 1_000) {
            throw new IllegalArgumentException("PBKDF2 iterations must be at least 1000");
        }
        this.iterations = iterations;
    }

    public static void validatePolicy(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at most " + MAX_PASSWORD_LENGTH + " characters");
        }
        boolean blank = true;
        for (char c : password) {
            if (!Character.isWhitespace(c)) {
                blank = false;
                break;
            }
        }
        if (blank) {
            throw new IllegalArgumentException("Password cannot be blank");
        }
    }

    public String hash(char[] password) {
        validatePolicy(password);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, iterations);
        var encoder = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    /** Constant-time comparison; malformed stored values simply fail verification. */
    public boolean verify(char[] password, String encoded) {
        if (password == null || password.length == 0 || password.length > MAX_PASSWORD_LENGTH || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (storedIterations < 1_000 || salt.length == 0 || expected.length == 0) {
                return false;
            }
            return MessageDigest.isEqual(expected, derive(password, salt, storedIterations));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** Spends about the same time as a real verification, for unknown usernames. */
    public void burnVerification(char[] password) {
        derive(password == null || password.length == 0 ? new char[]{'x'} : password, new byte[SALT_BYTES], iterations);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", error);
        } finally {
            spec.clearPassword();
        }
    }

    static void clear(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }
}
