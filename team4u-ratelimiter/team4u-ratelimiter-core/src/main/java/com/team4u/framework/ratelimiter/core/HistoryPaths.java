package com.team4u.framework.ratelimiter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Date;

/**
 * 历史路径点导航工具（history-window 专用，包内工具）
 * <p>
 * 按 {@code a.b.0.c} 形式的点路径从上下文中提取值：Map 按键取值、List 按数字
 * 下标取值、Bean 读公有 getter（取值逻辑收敛于 {@link ContextProperties}）。任一环节缺失
 * （键不存在、下标越界、无 getter）返回 null。
 * </p>
 *
 * @author jay.wu
 */
final class HistoryPaths {

    private HistoryPaths() {
    }

    /**
     * 提取历史时间戳列表：导航到路径终点后，将列表元素转为 epoch 毫秒
     * <p>
     * 终点非 List、路径缺失或为 null 返回空列表；元素仅支持 Number 与
     * {@link Date}（其余元素跳过，如 null 或字符串）。
     * </p>
     */
    static List<Long> extractTimestamps(Object context, String path) {
        Object value = navigate(context, path);
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<Long> timestamps = new ArrayList<>();
        for (Object element : (List<?>) value) {
            Long millis = toMillis(element);
            if (millis != null) {
                timestamps.add(millis);
            }
        }
        return timestamps;
    }

    /**
     * 按点路径逐段导航；任一段为 null 即终止返回 null
     */
    static Object navigate(Object root, String path) {
        if (root == null || path == null || path.trim().isEmpty()) {
            return null;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            String name = segment.trim();
            if (name.isEmpty()) {
                return null;
            }
            current = access(current, name);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * 单段访问：Map 取键 / List 取下标 / Bean 读公有 getter（经 {@link ContextProperties}）
     */
    private static Object access(Object target, String segment) {
        return ContextProperties.access(target, segment);
    }

    /**
     * 元素转 epoch 毫秒；不支持的类型返回 null
     */
    private static Long toMillis(Object element) {
        if (element instanceof Number) {
            return ((Number) element).longValue();
        }
        if (element instanceof Date) {
            return ((Date) element).getTime();
        }
        return null;
    }
}
