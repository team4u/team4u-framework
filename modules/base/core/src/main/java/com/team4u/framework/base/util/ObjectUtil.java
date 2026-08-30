package com.team4u.framework.base.util;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * 对象工具类
 * <p>
 * 提供对象判空、相等性比较、默认值处理等常用工具方法。
 *
 * @author jay.wu
 */
public class ObjectUtil {

    /**
     * 比较两个对象是否相等
     * <p>
     * 内部使用 {@link Objects#equals(Object, Object)} 实现。
     *
     * @param obj1 对象1
     * @param obj2 对象2
     * @return 如果两个对象相等则返回 true
     */
    public static boolean equal(Object obj1, Object obj2) {
        return Objects.equals(obj1, obj2);
    }

    /**
     * 若对象为 null 则返回指定的默认值
     *
     * @param value        待检查对象
     * @param defaultValue 默认值
     * @param <T>          对象类型
     * @return 如果 value 不为 null，则返回 value；否则返回 defaultValue
     */
    public static <T> T defaultIfNull(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }

    /**
     * 检查对象是否为空
     * <p>
     * 支持以下类型的空判断：
     * <ul>
     * <li>{@code null} - 返回 true</li>
     * <li>{@link CharSequence} - 长度是否为 0</li>
     * <li>{@link Collection} - 是否不包含任何元素</li>
     * <li>{@link Map} - 是否不包含任何键值对</li>
     * <li>Array - 数组长度是否为 0</li>
     * </ul>
     *
     * @param obj 待检查对象
     * @return 如果对象为空则返回 true
     */
    public static boolean isEmpty(Object obj) {
        if (obj == null) {
            return true;
        }

        if (obj instanceof CharSequence) {
            return ((CharSequence) obj).length() == 0;
        }

        if (obj instanceof Collection) {
            return ((Collection<?>) obj).isEmpty();
        }

        if (obj instanceof Map) {
            return ((Map<?, ?>) obj).isEmpty();
        }

        if (obj.getClass().isArray()) {
            return Array.getLength(obj) == 0;
        }

        return false;
    }

    /**
     * 检查对象是否不为空
     *
     * @param obj 待检查对象
     * @return 如果对象不为空则返回 true
     */
    public static boolean isNotEmpty(Object obj) {
        return !isEmpty(obj);
    }
}
