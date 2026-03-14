package com.team4u.framework.base.util;

import java.util.Collection;
import java.util.Map;

/**
 * 集合工具类
 * <p>
 * 提供针对集合（Collection）与可迭代对象的常用操作，如空值判断和大小计算。
 *
 * @author jay.wu
 */
public class CollectionUtil {

    /**
     * 判断集合是否为空
     *
     * @param collection 待检查的集合
     * @return 如果集合为 null 或不包含任何元素，返回 true，否则返回 false
     */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * 判断集合是否为非空
     *
     * @param collection 待检查的集合
     * @return 如果集合不为 null 且包含至少一个元素，返回 true，否则返回 false
     */
    public static boolean isNotEmpty(Collection<?> collection) {
        return !isEmpty(collection);
    }

    /**
     * 获取集合的大小
     *
     * @param collection 目标集合
     * @return 集合包含的元素数量，如果集合为 null 则返回 0
     */
    public static int size(Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

    /**
     * 获取对象的大小
     * <p>
     * 支持多种类型：集合（Collection）、Map、各种基本类型数组、对象数组以及可迭代对象（Iterable）。
     * 若为可迭代对象，将通过遍历计算元素总数。
     *
     * @param obj 待计算大小的对象
     * @return 对象包含的元素数量，若对象为空或类型不匹配则返回 0
     */
    public static int size(Object obj) {
        if (obj == null) {
            return 0;
        }

        if (obj instanceof Collection) {
            return ((Collection<?>) obj).size();
        } else if (obj instanceof Map) {
            return ((Map<?, ?>) obj).size();
        } else if (obj instanceof Object[]) {
            return ((Object[]) obj).length;
        } else if (obj instanceof int[]) {
            return ((int[]) obj).length;
        } else if (obj instanceof long[]) {
            return ((long[]) obj).length;
        } else if (obj instanceof double[]) {
            return ((double[]) obj).length;
        } else if (obj instanceof float[]) {
            return ((float[]) obj).length;
        } else if (obj instanceof byte[]) {
            return ((byte[]) obj).length;
        } else if (obj instanceof char[]) {
            return ((char[]) obj).length;
        } else if (obj instanceof short[]) {
            return ((short[]) obj).length;
        } else if (obj instanceof boolean[]) {
            return ((boolean[]) obj).length;
        } else if (obj instanceof Iterable) {
            int count = 0;
            for (Object ignored : (Iterable<?>) obj) {
                count++;
            }
            return count;
        }
        return 0;
    }
}
