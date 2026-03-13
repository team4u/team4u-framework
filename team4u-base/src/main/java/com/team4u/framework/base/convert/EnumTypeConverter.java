package com.team4u.framework.base.convert;

import java.lang.reflect.Type;

/**
 * 枚举类型转换器
 * <p>
 * 支持将字符串转换成枚举值，支持忽略大小写的匹配。
 *
 * @author jay.wu
 */
final class EnumTypeConverter extends AbstractTypeConverter {

    @Override
    public boolean supports(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        return type != null && type.isEnum();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object convert(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        if (type == null || !type.isEnum()) {
            return null;
        }
        String name = ConvertUtil.toStr(source, "");
        if (name.isEmpty()) {
            return null;
        }
        String candidate = name.trim();
        try {
            return Enum.valueOf((Class<? extends Enum>) type, candidate);
        } catch (IllegalArgumentException ignored) {
        }
        for (Object constant : type.getEnumConstants()) {
            Enum enumValue = (Enum) constant;
            if (enumValue.name().equalsIgnoreCase(candidate)) {
                return enumValue;
            }
        }
        return null;
    }

    @Override
    public int order() {
        return 20;
    }
}
