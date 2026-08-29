package com.team4u.framework.singleflight.core;

import com.team4u.framework.singleflight.policy.SingleFlightKeyDigest;

import java.nio.charset.StandardCharsets;

/**
 * 最终协调 key 的组装工具。
 * <p>
 * point 与业务 key 分别做百分号编码，再以下划线拼接，保证任意业务值
 * 都能安全进入 {@code SpaceKey}（分隔符不会被 {@code SpaceKey} 自身使用，point 与
 * 业务 key 之间不会产生歧义拼接）。规则声明了 {@code keyDigest} 时，业务 key 先经
 * 命名摘要策略变换再拼接——摘要只由规则手工指定，不再按 key 长度自动触发。
 * </p>
 *
 * @author jay.wu
 */
public final class SingleFlightKeys {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

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
     * 组装最终协调 key：业务 key 按需摘要，point 与业务 key 分别编码后拼接。
     *
     * @param point         切入点（规则标识，始终明文参与，便于排查定位）
     * @param renderedValue 渲染后的业务 key
     * @param digest        规则指定的摘要策略；null 表示不摘要，业务 key 明文进入存储
     * @return 可安全写入 KvStore 的最终 key
     */
    public static String compose(String point, String renderedValue, SingleFlightKeyDigest digest) {
        if (blank(point)) {
            throw new IllegalArgumentException("Singleflight point is invalid: " + point);
        }
        String businessKey = renderedValue;
        if (digest != null) {
            businessKey = digest.digest(renderedValue);
            if (blank(businessKey)) {
                throw new IllegalArgumentException(
                        "Singleflight digested key is empty|digest=" + digest.key());
            }
        } else if (blank(renderedValue)) {
            throw new IllegalArgumentException("Singleflight rendered key is empty");
        }
        return encode(point) + SEPARATOR + encode(businessKey);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 百分号编码：安全字符原样保留，其余字符按 UTF-8 字节逐个转义，
     * 确保编码结果只含安全字符与转义序列，与存储层 key 约束兼容。
     * 摘要输出也统一过编码兜底——hex 是 no-op，自定义算法返回非安全字符同样安全。
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
}
