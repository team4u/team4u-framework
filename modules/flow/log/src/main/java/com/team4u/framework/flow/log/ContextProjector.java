package com.team4u.framework.flow.log;

import com.team4u.framework.base.util.ReflectUtil;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;

/**
 * 流程上下文属性投影选择器 SPI。
 *
 * <p>负责在输出流程单步日志与最终汇总树前，从原始上下文对象中挑选/过滤出需要展示的属性字典或视图对象。</p>
 *
 * @author jay.wu
 */
@FunctionalInterface
public interface ContextProjector {

    /**
     * 将原始上下文对象转换为用于日志输出的精简属性对象或 Map。
     *
     * @param context 原始上下文对象，可能为 null
     * @return 过滤/挑选后的上下文视图或字典；若为 null 则在日志中输出空对象
     */
    Object project(Object context);

    /**
     * 创建基于 {@link TraceContext} 与 {@link TraceIgnore} 注解的自动扫描投影器。
     *
     * @return 注解驱动的投影器单例
     */
    static ContextProjector annotated() {
        return AnnotatedContextProjector.INSTANCE;
    }

    /**
     * 创建全量透传投影器（不执行任何属性过滤，直接输出原对象）。
     *
     * @return 全量投影器
     */
    static ContextProjector all() {
        return context -> context;
    }

    /**
     * 创建基于属性名称白名单的投影器。
     *
     * @param fieldNames 需输出的属性名称列表
     * @return 白名单投影器
     */
    static ContextProjector fields(String... fieldNames) {
        if (fieldNames == null || fieldNames.length == 0) {
            return context -> Collections.emptyMap();
        }
        return fields(Arrays.asList(fieldNames));
    }

    /**
     * 创建基于属性名称白名单集合的投影器。
     *
     * @param fieldNames 需输出的属性名称集合
     * @return 白名单投影器
     */
    static ContextProjector fields(Collection<String> fieldNames) {
        final Set<String> whitelist = new HashSet<String>(fieldNames != null ? fieldNames : Collections.<String>emptyList());
        return context -> {
            if (context == null) return null;
            if (context instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) context;
                Map<String, Object> filtered = new LinkedHashMap<String, Object>();
                for (String name : whitelist) {
                    if (map.containsKey(name)) {
                        filtered.put(name, map.get(name));
                    }
                }
                return filtered;
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            Map<String, Field> fieldMap = ReflectUtil.getFieldMap(context.getClass());
            for (String name : whitelist) {
                Field field = fieldMap.get(name);
                if (field != null) {
                    try {
                        result.put(name, field.get(context));
                    } catch (Exception ignored) {
                    }
                }
            }
            return result;
        };
    }

    /**
     * 创建基于自定义 Lambda 转换函数的强类型投影器。
     *
     * @param <T>      上下文类型
     * @param selector 字段挑选与转换函数
     * @return 函数式投影器
     */
    @SuppressWarnings("unchecked")
    static <T> ContextProjector of(Function<T, Object> selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return context -> context != null ? selector.apply((T) context) : null;
    }

    /**
     * 创建基于类型的多路路由投影器构建器。
     *
     * @return 路由投影器构建器
     */
    static TypeRoutingContextProjector.Builder byType() {
        return TypeRoutingContextProjector.builder();
    }

    /**
     * 根据指定的类型与投影器映射字典创建路由投影器。
     *
     * @param mappings 类型与投影器映射字典
     * @return 类型路由投影器
     */
    static ContextProjector byType(Map<Class<?>, ContextProjector> mappings) {
        return byType().bindAll(mappings).build();
    }
}
