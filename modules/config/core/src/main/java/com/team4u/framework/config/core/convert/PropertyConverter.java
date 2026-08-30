package com.team4u.framework.config.core.convert;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 自定义属性转换器 SPI 接口
 * <p>
 * 用于实现从字符串配置值到特定复杂类型的转换逻辑。
 * </p>
 *
 * @param <T> 目标类型
 */
public interface PropertyConverter<T> extends KeyedPolicy<Class<? extends PropertyConverter<?>>> {

    /**
     * 执行转换逻辑
     *
     * @param source     原始配置字符串（可能为 null）
     * @param targetType 期望转换的目标类型
     * @return 转换后的实例对象
     */
    T convert(String source, Class<T> targetType);

    @Override
    @SuppressWarnings("unchecked")
    default Class<? extends PropertyConverter<?>> key() {
        return (Class<? extends PropertyConverter<?>>) getClass();
    }
}
