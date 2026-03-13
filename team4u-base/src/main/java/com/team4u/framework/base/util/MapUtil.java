package com.team4u.framework.base.util;

import com.team4u.framework.base.convert.ConvertUtil;

import java.util.Map;

/**
 * Map 工具类
 * <p>
 * 提供针对 {@link Map} 的判空、路径取值等常用操作。
 *
 * @author jay.wu
 */
public class MapUtil {

    /**
     * 判断 Map 是否为空
     *
     * @param map 待检查的 Map
     * @return 如果 Map 为 null 或不包含任何键值对，则返回 true
     */
    public static boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * 判断 Map 是否不为空
     *
     * @param map 待检查的 Map
     * @return 如果 Map 不为 null 且包含键值对，则返回 true
     */
    public static boolean isNotEmpty(Map<?, ?> map) {
        return !isEmpty(map);
    }

    /**
     * 根据路径获取 Map 中的值
     * <p>
     * 路径以 "." 分隔，例如 "user.address.city"。
     * 如果路径中的某个节点不是 Map 或值为 null，则返回 null。
     *
     * @param map  目标 Map
     * @param path 属性路径
     * @param type 返回值类型
     * @param <T>  泛型类型
     * @return 路径对应的值，若未找到或转换失败则返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T getByPath(Map<?, ?> map, String path, Class<T> type) {
        if (map == null || path == null || path.isEmpty()) {
            return null;
        }

        Object result = map;
        String[] keys = path.split("\\.");
        for (String key : keys) {
            if (result instanceof Map) {
                result = ((Map<?, ?>) result).get(key);
            } else {
                return null;
            }
        }

        if (result == null) {
            return null;
        }

        return ConvertUtil.convert(type, result);
    }
}
