package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.FastMasker;
import com.team4u.framework.mask.MaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 枚举与参数化策略的绑定器
 * <p>
 * 历史』B1A1/B2A2/PERCENT66 等「一种参数组合一个壳类」的实现已收敛为
 * {@link PrefixSuffixMaskPolicy} / {@link PercentMaskPolicy} 两个参数化策略；
 * 本绑定器在类加载时按 {@link MaskType} 枚举名注册对应的参数化实例，
 * 对外保持「枚举名 -> 策略实例」的映射不变。
 *
 * @author jay.wu
 */
public final class MaskPolicyBinder {

    private static final Logger log = LoggerFactory.getLogger(MaskPolicyBinder.class);

    private MaskPolicyBinder() {
    }

    /**
     * 注册参数化策略到 {@link FastMasker}（幂等，仅注册一次）
     */
    public static void bind() {
        // 前后缀保留类
        FastMasker.register(new PrefixSuffixMaskPolicy(MaskType.B1A1.name(), 1, 1));
        FastMasker.register(new PrefixSuffixMaskPolicy(MaskType.B2A2.name(), 2, 2));
        // 百分比掩码类
        FastMasker.register(new PercentMaskPolicy(MaskType.PERCENT66.name(), 66));
        FastMasker.register(new PercentMaskPolicy(MaskType.PERCENT66_LIMIT10.name(), 66, 10));
        FastMasker.register(new PercentMaskPolicy(MaskType.PERCENT1_LIMIT200.name(), 1, 200));
        log.debug("MaskPolicyBinder|bind|success|parameterizedPolicies=5");
    }
}
