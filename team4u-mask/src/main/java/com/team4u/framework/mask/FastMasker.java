package com.team4u.framework.mask;

import com.team4u.framework.policy.util.PolicyScanner;

/**
 * High-performance fail-closed mask facade.
 */
public final class FastMasker {

    private static final MaskPolicyRegistry REGISTRY =
            new MaskPolicyRegistry(MaskPolicy.class);

    static {
        PolicyScanner.scanAndRegister(REGISTRY);
        PolicyScanner.registerFromServiceLoader(REGISTRY);
    }

    private FastMasker() {
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
