package com.team4u.framework.base.convert;

import com.team4u.framework.base.util.BeanUtil;
import com.team4u.framework.base.util.CopyOptions;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Bean 类型转换器
 * <p>
 * 支持将 Map 转换为指定的 Bean 对象。
 *
 * @author jay.wu
 */
final class BeanTypeConverter extends AbstractTypeConverter {

    @Override
    public boolean supports(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        return type != null && source instanceof Map && !Map.class.isAssignableFrom(type);
    }

    @Override
    public Object convert(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        return type == null ? null : BeanUtil.toBean((Map<?, ?>) source, type, CopyOptions.create());
    }

    @Override
    public int order() {
        return 60;
    }
}
