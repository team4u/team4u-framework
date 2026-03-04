package com.team4u.mask;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.util.PolicyScanner;

import java.util.Optional;

/**
 * 高性能掩码处理器
 * <p>
 * 基于 {@link KeyedPolicyRegistry} 实现，支持通过字符串 Key 进行动态路由。
 * 内置标准脱敏算法，并支持通过 SPI 或 {@link #register(MaskPolicy)} 注册自定义脱敏算法。
 */
public class FastMasker {

    private static final KeyedPolicyRegistry<String, MaskPolicy> REGISTRY = new KeyedPolicyRegistry<>(MaskPolicy.class);

    static {
        // 1. 自动扫描内置策略包
        PolicyScanner.scanAndRegister(REGISTRY);
        // 2. 支持通过 SPI 加载扩展策略
        PolicyScanner.registerFromServiceLoader(REGISTRY);
    }

    /**
     * 编程式注册脱敏策略
     *
     * @param policy 脱敏策略实现
     */
    public static void register(MaskPolicy policy) {
        REGISTRY.register(policy);
    }

    /**
     * 执行标准脱敏处理
     *
     * @param value 原始字符串
     * @param type  内置脱敏类型枚举
     * @return 脱敏后的字符串
     */
    public static String mask(String value, MaskType type) {
        return mask(value, type.name());
    }

    /**
     * 执行动态脱敏处理 (支持自定义标识)
     *
     * @param value 原始字符串
     * @param type  脱敏算法标识 (如 "PHONE", "EMAIL", "BANK_CARD")
     * @return 脱敏后的字符串
     */
    public static String mask(String value, String type) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        Optional<MaskPolicy> policyOptional = REGISTRY.get(type);
        if (policyOptional.isPresent()) {
            return policyOptional.get().mask(value);
        }

        return value;
    }
}