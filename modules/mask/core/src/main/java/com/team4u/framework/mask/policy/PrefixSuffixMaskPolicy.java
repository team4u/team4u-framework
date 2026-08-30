package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskUtils;

/**
 * 前后缀保留脱敏策略（参数化）
 * <p>
 * 保留值的前 keepPrefix 与后 keepSuffix 个字符（按 Unicode code point），
 * 中间部分以 * 掩码。替代历史上 B1A1MaskPolicy / B2A2MaskPolicy 等
 * 「一种参数组合一个类」的纯壳实现。
 * <p>
 * 本类不绑定固定 key，由 {@link MaskPolicyBinder} 按 {@link com.team4u.framework.mask.MaskType}
 * 枚举名注册对应实例。
 *
 * @author jay.wu
 */
public class PrefixSuffixMaskPolicy extends AbstractKeyedMaskPolicy {

    private final int keepPrefix;
    private final int keepSuffix;

    /**
     * @param key         策略标识（通常为 MaskType 枚举名）
     * @param keepPrefix  前缀保留长度
     * @param keepSuffix  后缀保留长度
     */
    public PrefixSuffixMaskPolicy(String key, int keepPrefix, int keepSuffix) {
        super(key);
        this.keepPrefix = keepPrefix;
        this.keepSuffix = keepSuffix;
    }

    @Override
    public String mask(String value) {
        return MaskUtils.mask(value, keepPrefix, keepSuffix);
    }
}
