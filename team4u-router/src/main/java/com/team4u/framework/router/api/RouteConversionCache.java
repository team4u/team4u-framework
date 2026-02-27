package com.team4u.framework.router.api;

import cn.hutool.core.convert.Convert;
import com.team4u.framework.base.instance.DynamicInstanceProvider;
import lombok.Data;

/**
 * 路由结果转换缓存
 * <p>
 * 缓存类型安全路由的类型转换结果，避免重复进行反射与转换的性能开销
 * </p>
 */
public class RouteConversionCache {

    private static final DynamicInstanceProvider<ConversionKey, ConversionKey, Object> PROVIDER =
            DynamicInstanceProvider.createLru(
                    1000,
                    key -> key,
                    key -> Convert.convert(key.getTargetType(), key.getValue())
            );

    /**
     * 将路由结果值转换为目标类型并进行缓存
     *
     * @param value      原始值
     * @param targetType 期望转换的目标类型
     * @param <T>        目标类型
     * @return 转换后的值
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object value, Class<T> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType != null && targetType.isInstance(value)) {
            return (T) value;
        }
        return (T) PROVIDER.get(new ConversionKey(value, targetType));
    }

    @Data
    private static class ConversionKey {
        private final Object value;
        private final Class<?> targetType;
    }
}
