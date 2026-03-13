package com.team4u.framework.base.util;

import lombok.Getter;

import java.lang.reflect.Type;

/**
 * 泛型类型引用。
 *
 * @param <T> 具体的泛型类型
 */
@Getter
public abstract class TypeReference<T> {

    /**
     * 持有的泛型类型
     */
    private final Type type;

    protected TypeReference() {
        Type resolvedType = TypeUtil.getTypeArgument(getClass(), 0);
        if (resolvedType == null) {
            throw new IllegalArgumentException("TypeReference 必须包含具体的泛型参数。");
        }
        this.type = resolvedType;
    }
}
