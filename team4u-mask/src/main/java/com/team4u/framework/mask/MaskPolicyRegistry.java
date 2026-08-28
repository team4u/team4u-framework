package com.team4u.framework.mask;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * Thread-safe in-process registry for mask policies.
 */
public final class MaskPolicyRegistry extends KeyedPolicyRegistry<String, MaskPolicy> {

    public MaskPolicyRegistry(Class<?> policyClass) {
        super(policyClass);
    }
}
