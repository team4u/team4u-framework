package com.team4u.config.core.convert;

import com.team4u.framework.policy.KeyedPolicyRegistry;
import com.team4u.framework.policy.PolicyScanner;

/**
 * 自定义转换器注册表
 * <p>
 * 继承自 {@link KeyedPolicyRegistry}，用于管理 {@link PropertyConverter} 实例。
 */
public class PropertyConverterRegistry
        extends KeyedPolicyRegistry<Class<? extends PropertyConverter<?>>, PropertyConverter<?>> {

    public PropertyConverterRegistry() {
        super((Class<PropertyConverter<?>>) (Class<?>) PropertyConverter.class);
        // 自动从 SPI 加载
        PolicyScanner.registerFromServiceLoader(this);
        // 自动扫描所在包下的实现
        PolicyScanner.scanAndRegister(this);
    }
}
