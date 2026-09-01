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
        return clazz.isAssignableFrom(targetType.rawType());
    }

    @Override
    public String toString() {
        return clazz.getSimpleName();
    }
}
