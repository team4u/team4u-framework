package com.team4u.framework.mask;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 脱敏策略接口
 * <p>
 * 继承自 {@link KeyedPolicy}，支持基于字符串 Key 的动态路由，实现“野马”级别的扩展。
 */
public interface MaskPolicy extends KeyedPolicy<String> {

    /**
     * 执行脱敏处理
     *
     * @param value 原始字符串
     * @return 脱敏后的字符串
     */
    String mask(String value);
}
