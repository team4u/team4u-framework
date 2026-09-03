package com.team4u.framework.flow.definition.type;

import lombok.EqualsAndHashCode;

import java.util.Objects;

/**
 * 基于标准 Java Class 的类型引用实现。
 *
 * @author jay.wu
 */
@EqualsAndHashCode
public final class ClassTypeRef implements TypeRef {
    private static final long serialVersionUID = 1L;

    private final Class<?> clazz;

    public ClassTypeRef(Class<?> clazz) {
        this.clazz = Objects.requireNonNull(clazz, "class must not be null");
    }

    @Override
    public Class<?> rawType() {
        return clazz;
    }

    @Override
    public String typeName() {
        return clazz.getName();
    }

    @Override
    public boolean isAssignableFrom(TypeRef targetType) {
        if (targetType == null) {
            return false;
        }
        if (clazz == Object.class) {
            return true;
        }
        Class<?> targetRaw = targetType.rawType();
        if (targetRaw == null) {
            return false;
        }
        if (clazz.isAssignableFrom(targetRaw)) {
            return true;
        }
        return isBoxingCompatible(clazz, targetRaw);
    }

    public static boolean isBoxingCompatible(Class<?> a, Class<?> b) {
        if (a == int.class && b == Integer.class || a == Integer.class && b == int.class) return true;
        if (a == boolean.class && b == Boolean.class || a == Boolean.class && b == boolean.class) return true;
        if (a == long.class && b == Long.class || a == Long.class && b == long.class) return true;
        if (a == double.class && b == Double.class || a == Double.class && b == double.class) return true;
        if (a == float.class && b == Float.class || a == Float.class && b == float.class) return true;
        if (a == byte.class && b == Byte.class || a == Byte.class && b == byte.class) return true;
        if (a == short.class && b == Short.class || a == Short.class && b == short.class) return true;
        if (a == char.class && b == Character.class || a == Character.class && b == char.class) return true;
        return false;
    }

    @Override
    public String toString() {
        return clazz.getSimpleName();
    }
}
