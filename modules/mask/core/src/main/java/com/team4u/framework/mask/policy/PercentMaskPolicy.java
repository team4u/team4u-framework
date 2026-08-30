package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskUtils;

/**
 * 百分比掩码脱敏策略（参数化）
 * <p>
 * 对值的 percent% 部分执行居中掩码，可选再限制最大显示长度。
 * 替代历史上 Percent66MaskPolicy / Percent66Limit10MaskPolicy /
 * Percent1Limit200MaskPolicy 等「一种参数组合一个类」的纯壳实现。
 * <p>
 * 本类不绑定固定 key，由 {@link MaskPolicyBinder} 按 {@link com.team4u.framework.mask.MaskType}
 * 枚举名注册对应实例。
 *
 * @author jay.wu
 */
public class PercentMaskPolicy extends AbstractKeyedMaskPolicy {

    private final int percent;
    private final int limit;

    /**
     * 不限长度的百分比掩码
     *
     * @param key     策略标识（通常为 MaskType 枚举名）
     * @param percent 掩码部分占总长度的百分比 (0-100)
     */
    public PercentMaskPolicy(String key, int percent) {
        this(key, percent, -1);
    }

    /**
     * @param key     策略标识（通常为 MaskType 枚举名）
     * @param percent 掩码部分占总长度的百分比 (0-100)
     * @param limit   掩码后最大显示长度，小于等于 0 表示不限制
     */
    public PercentMaskPolicy(String key, int percent, int limit) {
        super(key);
        this.percent = percent;
        this.limit = limit;
    }

    @Override
    public String mask(String value) {
        String masked = MaskUtils.maskByPercent(value, percent);
        return limit > 0 ? MaskUtils.limit(masked, limit) : masked;
    }
}
