package com.team4u.framework.flow.log;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 基于对象类型的多路路由上下文投影器实现。
 *
 * <p>特性与契约：
 * <ul>
 *   <li><b>多类型绑定</b>：支持为不同 DTO / 实体类型独立配置专属的投影器（如字段白名单、Lambda 自定义转换函数或嵌套投影器）；</li>
 *   <li><b>函数式融入</b>：支持直接通过 {@code bind(Type.class, (Type obj) -> ...)} 融入自定义计算/重命名字段逻辑；</li>
 *   <li><b>继承体系匹配与缓存</b>：先精确匹配当前类，未命中时向上递归匹配父类或接口，并将匹配结果缓存至并发字典；</li>
 *   <li><b>未匹配兜底回退</b>：未显式注册的类型支持配置 {@code fallback}（例如回退到 {@link ContextProjector#annotated()} 或原样返回）。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public class TypeRoutingContextProjector implements ContextProjector {

    private final Map<Class<?>, ContextProjector> registry;
    private final ContextProjector fallback;
    private final Map<Class<?>, Optional<ContextProjector>> resolvedCache = new ConcurrentHashMap<Class<?>, Optional<ContextProjector>>();

    TypeRoutingContextProjector(Map<Class<?>, ContextProjector> registry, ContextProjector fallback) {
        this.registry = Collections.unmodifiableMap(new HashMap<Class<?>, ContextProjector>(registry));
        this.fallback = fallback;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Object project(Object context) {
        if (context == null) {
            return null;
        }

        Class<?> clazz = context.getClass();
        Optional<ContextProjector> projectorOpt = resolvedCache.computeIfAbsent(clazz, this::findProjectorForClass);

        if (projectorOpt.isPresent()) {
            return projectorOpt.get().project(context);
        }

        if (fallback != null) {
            return fallback.project(context);
        }

        return context;
    }

    private Optional<ContextProjector> findProjectorForClass(Class<?> clazz) {
        ContextProjector exact = registry.get(clazz);
        if (exact != null) {
            return Optional.of(exact);
        }

        // 向上查找父类或接口中注册的投影器
        for (Map.Entry<Class<?>, ContextProjector> entry : registry.entrySet()) {
            if (entry.getKey().isAssignableFrom(clazz)) {
                return Optional.of(entry.getValue());
            }
        }

        return Optional.empty();
    }

    public static final class Builder {
        private final Map<Class<?>, ContextProjector> registry = new LinkedHashMap<Class<?>, ContextProjector>();
        private ContextProjector fallback = ContextProjector.annotated();

        Builder() {
        }

        /**
         * 针对指定类型绑定专属的投影器。
         *
         * @param type      目标类型
         * @param projector 投影器
         * @return 当前构建器
         */
        public Builder bind(Class<?> type, ContextProjector projector) {
            if (type != null && projector != null) {
                this.registry.put(type, projector);
            }
            return this;
        }

        /**
         * 针对指定类型绑定强类型的自定义转换函数（融入 of 自定义值能力）。
         *
         * @param type     目标类型
         * @param selector 自定义挑选/转换函数
         * @param <T>      目标类型泛型
         * @return 当前构建器
         */
        public <T> Builder bind(Class<T> type, Function<T, Object> selector) {
            if (type != null && selector != null) {
                this.registry.put(type, ContextProjector.of(selector));
            }
            return this;
        }

        /**
         * 针对指定类型绑定属性白名单列表。
         *
         * @param type       目标类型
         * @param fieldNames 白名单属性名称
         * @return 当前构建器
         */
        public Builder bindFields(Class<?> type, String... fieldNames) {
            if (type != null) {
                this.registry.put(type, ContextProjector.fields(fieldNames));
            }
            return this;
        }

        /**
         * 针对指定类型绑定属性白名单集合。
         *
         * @param type       目标类型
         * @param fieldNames 白名单属性集合
         * @return 当前构建器
         */
        public Builder bindFields(Class<?> type, Collection<String> fieldNames) {
            if (type != null) {
                this.registry.put(type, ContextProjector.fields(fieldNames));
            }
            return this;
        }

        /**
         * 批量绑定映射字典。
         *
         * @param mappings 类型与投影器映射字典
         * @return 当前构建器
         */
        public Builder bindAll(Map<Class<?>, ContextProjector> mappings) {
            if (mappings != null) {
                mappings.forEach(this::bind);
            }
            return this;
        }

        /**
         * 设置未命中已注册类型时的兜底回退投影器。
         *
         * @param fallback 兜底投影器（可传 null 表示未匹配时原样透传）
         * @return 当前构建器
         */
        public Builder fallback(ContextProjector fallback) {
            this.fallback = fallback;
            return this;
        }

        /**
         * 构建类型路由投影器。
         *
         * @return 路由投影器实例
         */
        public TypeRoutingContextProjector build() {
            return new TypeRoutingContextProjector(registry, fallback);
        }
    }
}
