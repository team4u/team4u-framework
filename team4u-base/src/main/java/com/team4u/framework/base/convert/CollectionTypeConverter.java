package com.team4u.framework.base.convert;

import com.team4u.framework.base.util.ReflectUtil;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 集合类型转换器
 * <p>
 * 支持将对象转换为各种 Collection 实现类，如 List、Set 等，并支持泛型元素转换。
 *
 * @author jay.wu
 */
final class CollectionTypeConverter extends AbstractTypeConverter {

    @Override
    public boolean supports(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        return type != null && Collection.class.isAssignableFrom(type);
    }

    @Override
    public Object convert(Type targetType, Object source) {
        Class<?> rawType = toClass(targetType);
        Collection<Object> result = createCollection(rawType);
        Type elementType = extractElementType(targetType);
        List<?> rawList = ConvertUtil.toList(source);
        for (Object item : rawList) {
            result.add(elementType == null ? item : ConvertUtil.convert(elementType, item));
        }
        return result;
    }

    /**
     * 根据目标类型创建集合实例
     *
     * @param rawType 目标集合的原始类类型
     * @return 集合实例，无法创建时返回默认的 ArrayList
     */
    @SuppressWarnings("unchecked")
    private Collection<Object> createCollection(Class<?> rawType) {
        if (rawType == null || rawType == Collection.class || rawType == List.class) {
            return new ArrayList<>();
        }
        if (rawType == Set.class) {
            return new LinkedHashSet<>();
        }
        if (!rawType.isInterface() && !Modifier.isAbstract(rawType.getModifiers())) {
            Object instance = ReflectUtil.newInstance(rawType);
            if (instance instanceof Collection) {
                return (Collection<Object>) instance;
            }
        }
        if (Set.class.isAssignableFrom(rawType)) {
            return new LinkedHashSet<>();
        }
        return new ArrayList<>();
    }

    /**
     * 提取集合的元素泛型类型
     *
     * @param targetType 参数化类型
     * @return 元素类型，如果不是参数化类型则返回 null
     */
    private Type extractElementType(Type targetType) {
        if (!(targetType instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType parameterizedType = (ParameterizedType) targetType;
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        return typeArguments.length == 0 ? null : typeArguments[0];
    }

    @Override
    public int order() {
        return 40;
    }
}
