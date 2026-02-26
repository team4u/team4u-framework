package com.team4u.framework.criterion.model.convert;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.util.PolicyScanner;

/**
 * 转换器注册表
 * <p>
 * 管理所有 ValueConverter 实现，支持自动扫描和动态注册
 *
 * @author jay.wu
 */
public class ValueConverterRegistry extends KeyedPolicyRegistry<String, ValueConverter> {

    private static final ValueConverterRegistry GLOBAL = new ValueConverterRegistry();

    static {
        // 自动扫描当前包及其子包并注册
        PolicyScanner.scanAndRegister(GLOBAL);
        // 通过 ServiceLoader 加载
        PolicyScanner.registerFromServiceLoader(GLOBAL);
    }

    /**
     * 获取全局共享的转换器注册表实例
     *
     * @return 全局注册表实例
     */
    public static ValueConverterRegistry global() {
        return GLOBAL;
    }

    public ValueConverterRegistry() {
        super(ValueConverter.class);
    }
    public ValueConverter policyOf(String id) {
        return get(id).orElse(null);
    }
}
