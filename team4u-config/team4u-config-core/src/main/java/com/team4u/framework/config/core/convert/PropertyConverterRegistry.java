package com.team4u.framework.config.core.convert;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 属性转换器注册表
 * <p>
 * 用于统一管理和检索已注册的 {@link PropertyConverter} 实例。
 * 继承自 {@link KeyedPolicyRegistry}，通过转换器的类名进行唯一标识。
 * </p>
 */
public class PropertyConverterRegistry
        extends KeyedPolicyRegistry<Class<? extends PropertyConverter<?>>, PropertyConverter<?>> {

    public PropertyConverterRegistry() {
        super(PropertyConverter.class);
    }
}
