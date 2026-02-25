package com.team4u.config.core.convert;

import com.team4u.framework.policy.KeyedPolicy;

/**
 * 自定义属性转换器 SPI
 *
 * @param <T> 目标类型
 */
public interface PropertyConverter<T> extends KeyedPolicy<Class<? extends PropertyConverter<?>>> {

    /**
     * 将配置的原始字符串转换为目标对象
     *
     * @param source     原始配置值 (nullable)
     * @param targetType 目标类型
     * @return 转换后的对象
     */
    T convert(String source, Class<T> targetType);

    @Override
    @SuppressWarnings("unchecked")
    default Class<? extends PropertyConverter<?>> key() {
        return (Class<? extends PropertyConverter<?>>) getClass();
    }
}
