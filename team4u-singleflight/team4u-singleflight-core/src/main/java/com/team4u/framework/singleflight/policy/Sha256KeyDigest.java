package com.team4u.framework.singleflight.policy;

import com.team4u.framework.base.util.DigestUtil;

/**
 * 内置 SHA-256 摘要策略：对业务 key 全量摘要，不保留任何可读前缀——
 * 短 key（如手机号）留前缀等于没有脱敏。注意 SHA-256 对低熵
 * 标识符不具备抗穷举能力，有此要求请自行实现 HMAC 并注册覆盖。
 * </p>
 *
 * @author jay.wu
 */
public class Sha256KeyDigest implements SingleFlightKeyDigest {

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
        return DigestUtil.sha256Hex(renderedKey);
    }
}
