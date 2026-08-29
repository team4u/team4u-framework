package com.team4u.framework.singleflight.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内置 SHA-256 摘要策略：对业务 key 全量摘要，不保留任何可读前缀——
 * 短 key（如手机号）留前缀等于没有脱敏。注意 SHA-256 对低熵
 * 标识符不具备抗穷举能力，有此要求请自行实现 HMAC 并注册覆盖。
 * </p>
 *
 * @author jay.wu
 */
public class Sha256KeyDigest implements SingleFlightKeyDigest {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    public String key() {
        return "sha256";
    }

    @Override
    public String digest(String renderedKey) {
        if (renderedKey == null || renderedKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "Singleflight rendered key is empty");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(renderedKey.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                out[i * 2] = HEX[value >>> 4];
                out[i * 2 + 1] = HEX[value & 0xf];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
