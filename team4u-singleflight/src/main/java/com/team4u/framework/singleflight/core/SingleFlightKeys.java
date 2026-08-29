package com.team4u.framework.singleflight.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 最终协调 key 的渲染工具。
 * <p>
 * point 与渲染后的业务 key 分别做百分号编码，再以下划线拼接，保证任意业务值
 * 都能安全进入 {@code SpaceKey}（分隔符不会被 {@code SpaceKey} 自身使用，point 与
 * 业务 key 之间不会产生歧义拼接）。编码后的完整 key 长度超过阈值时，保留可读前缀
 * 并追加 SHA-256 摘要，避免存储 key 随业务值无界变长。
 * </p>
 *
 * @author jay.wu
 */
public final class SingleFlightKeys {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /**
     * 摘要后保留的可读前缀长度：兼顾排查时的可读性与 key 长度上限
     */
    private static final int READABLE_PREFIX_LENGTH = 48;
    /**
     * 免编码的安全字符集（RFC 3986 Unreserved Characters）
     */
    private static final String SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            + "abcdefghijklmnopqrstuvwxyz0123456789.-~";
    /**
     * point 与业务 key 的分隔符
     */
    private static final char SEPARATOR = '_';

    private SingleFlightKeys() {
    }

    /**
     * 组装最终协调 key：point 与业务 key 分别编码、拼接，超长时做摘要。
     *
     * @param point         切入点（规则标识）
     * @param renderedValue 渲染后的业务 key
     * @param digestThreshold 摘要触发阈值（编码后完整 key 长度）
     * @return 可安全写入 KvStore 的最终 key
     */
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

    /**
     * 百分号编码：安全字符原样保留，其余字符按 UTF-8 字节逐个转义，
     * 确保编码结果只含安全字符与转义序列，与存储层 key 约束兼容。
     */
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

    /**
     * 超长 key 摘要：保留可读前缀并追加完整 key 的 SHA-256 十六进制摘要，
     * 摘要涵盖未截断的完整编码 key，不同长 key 不会因截断而碰撞。
     */
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
