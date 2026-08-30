package com.team4u.framework.base.util;

/**
 * 数组工具类
 * <p>
 * 提供针对数组的常用操作，如空值检查等。
 *
 * @author jay.wu
 */
public class ArrayUtil {

    /**
     * 判断数组是否为空
     * <p>
     * 数组对象为 null 或者数组长度为 0 时返回 true。
     *
     * @param array 待检查的数组对象
     * @return 如果数组为空则返回 true，否则返回 false
     */
    public static boolean isEmpty(Object[] array) {
        return array == null || array.length == 0;
    }

    /**
     * 判断数组是否为非空
     * <p>
     * 数组对象不为 null 且数组长度大于 0 时返回 true。
     *
     * @param array 待检查的数组对象
     * @return 如果数组不为空则返回 true，否则返回 false
     */
    public static boolean isNotEmpty(Object[] array) {
        return !isEmpty(array);
    }
}
