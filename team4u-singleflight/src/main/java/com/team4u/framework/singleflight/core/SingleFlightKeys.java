package com.team4u.framework.singleflight.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Final singleflight key rendering.
 * <p>
 * Both the point and the rendered business key are percent-encoded, then joined
 * with an underscore. The separator is never used by {@code SpaceKey}, and the
 * complete encoded key is digested when it exceeds the configured threshold.
 * </p>
 *
 * @author jay.wu
 */
public final class SingleFlightKeys {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int READABLE_PREFIX_LENGTH = 48;
    private static final String SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            + "abcdefghijklmnopqrstuvwxyz0123456789.-~";
    private static final char SEPARATOR = '_';

    private SingleFlightKeys() {
    }

    public static String compose(String point, String renderedValue, int digestThreshold) {
        if (blank(point)) {
            throw new IllegalArgumentException("Singleflight point is invalid: " + point);
        }
        if (blank(renderedValue)) {
            throw new IllegalArgumentException("Singleflight rendered key is empty");
        }
        if (digestThreshold <= 0) {
            throw new IllegalArgumentException("Singleflight digestThreshold must be > 0");
        }
        String encoded = encode(point) + SEPARATOR + encode(renderedValue);
        return digest(encoded, digestThreshold);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String encode(String value) {
        StringBuilder encoded = new StringBuilder(Math.min(value.length() * 2, 1024));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (SAFE.indexOf(c) >= 0) {
                encoded.append(c);
                continue;
            }
            byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                encoded.append('%').append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
            }
        }
        return encoded.toString();
    }

    private static String digest(String sanitized, int threshold) {
        if (sanitized.length() <= threshold) {
            return sanitized;
        }
        String prefix = sanitized.substring(0, Math.min(READABLE_PREFIX_LENGTH, sanitized.length()));
        return prefix + "#sha256_" + sha256Hex(sanitized);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xff;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0xf];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
