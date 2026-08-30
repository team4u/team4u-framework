package com.team4u.framework.mask;

import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.mask.policy.MaskPolicyBinder;

/**
 * High-performance fail-closed mask facade.
 */
public class FastMasker {

    private static final MaskPolicyRegistry REGISTRY =
            new MaskPolicyRegistry(MaskPolicy.class);

    static {
        PolicyScanner.scanAndRegister(REGISTRY);
        // 注册枚举名到参数化策略的绑定（B1A1/B2A2/PERCENT66 等历史壳类已收敛）
        MaskPolicyBinder.bind();
        // 支持通过 SPI 加载扩展策略
        PolicyScanner.registerFromServiceLoader(REGISTRY);
    }

    public FastMasker() {
    }

    public static void register(MaskPolicy policy) {
        REGISTRY.register(policy);
    }

    public static String mask(String value, MaskType type) {
        if (type == null) {
            throw new IllegalArgumentException("Mask policy must not be null");
        }
        return mask(value, type.name());
    }

    public static String mask(String value, String type) {
        // Resolve first so a null or empty value can never bypass an invalid policy key.
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Mask policy must not be null or empty");
        }

        MaskPolicy policy = REGISTRY.get(type)
                .orElseThrow(() -> new IllegalArgumentException("Unknown mask policy: " + type));
        if (value == null || value.isEmpty()) {
            return value;
        }
        return policy.mask(value);
    }
}
