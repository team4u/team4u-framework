package com.team4u.framework.base.convert;

import java.lang.reflect.Type;

/**
 * 数组类型转换器
 *
 * @author jay.wu
 */
final class ArrayTypeConverter extends AbstractTypeConverter {

    @Override
    public boolean supports(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        return type != null && type.isArray();
    }

    @Override
    public Object convert(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        return type == null ? null : ConvertUtil.toArray(type.getComponentType(), source);
    }

    @Override
    public int order() {
        return 50;
    }
}
