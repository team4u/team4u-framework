package com.team4u.framework.criterion.model.convert;

import com.team4u.framework.policy.KeyedPolicyRegistry;

/**
 * 转换器注册表
 * <p>
 * 管理所有 ValueConverter 实现，支持自动扫描和动态注册
 *
 * @author jay.wu
 */
public class ValueConverterRegistry extends KeyedPolicyRegistry<String, ValueConverter> {

    public ValueConverterRegistry() {
        super(ValueConverter.class);
    }
    public ValueConverter policyOf(String id) {
        return get(id).orElse(null);
    }
}
