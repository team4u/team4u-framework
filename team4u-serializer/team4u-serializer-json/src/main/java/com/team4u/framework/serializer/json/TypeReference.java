package com.team4u.framework.serializer.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 泛型类型引用。
 * <p>
 * 使用方法：
 * <pre>
 * List&lt;String&gt; list = JsonUtil.toBean(json, new TypeReference&lt;List&lt;String&gt;&gt;() {});
 * </pre>
 *
 * @param <T> 具体的泛型类型
 * @author jay.wu
 */
public abstract class TypeReference<T> {

    private final Type type;

    protected TypeReference() {
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof Class<?>) {
            throw new IllegalArgumentException("TypeReference 必须包含具体的泛型参数。");
        }
        this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
    }

    /**
     * 获取持有的泛型类型
     */
    public Type getType() {
        return type;
    }
}
