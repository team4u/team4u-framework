package com.team4u.framework.router.api.builder;

import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由策略流式构建器
 *
 * @param <T> 目标路由值的类型 (用于编译期类型安全检查)
 */
public class RoutePolicyBuilder<T> {

    private final String type;
    private final List<RouteRule> rules = new ArrayList<>();
    private final Map<String, Object> ext = new HashMap<>();
    private String id;
    private T fallbackValue;

    // 私有化构造器，强制使用静态工厂方法
    private RoutePolicyBuilder(String type) {
        this.type = type;
    }

    /**
     * 创建一个 Map (映射) 类型的路由策略构建器
     */
    public static <T> RoutePolicyBuilder<T> map() {
        return new RoutePolicyBuilder<>(RouterType.MAP);
    }

    /**
     * 创建一个 Expression (表达式) 类型的路由策略构建器
     */
    public static <T> RoutePolicyBuilder<T> expression() {
        return new RoutePolicyBuilder<>(RouterType.EXPRESSION);
    }

    /**
     * 创建一个 Weight (权重) 类型的路由策略构建器
     */
    public static <T> RoutePolicyBuilder<T> weight() {
        return new RoutePolicyBuilder<>(RouterType.WEIGHT);
    }

    /**
     * 创建一个自定义类型的路由策略构建器 (用于 SPI 扩展)
     *
     * @param type 路由器类型标识
     */
    public static <T> RoutePolicyBuilder<T> custom(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Router type cannot be empty");
        }
        return new RoutePolicyBuilder<>(type);
    }

    /**
     * 设置路由唯一标识 (可选，但在打日志和 Trace 时很有用)
     */
    public RoutePolicyBuilder<T> id(String id) {
        this.id = id;
        return this;
    }

    /**
     * 添加一条路由规则
     *
     * @param condition 匹配条件 (Map的Key 或 表达式)
     * @param value     命中后返回的目标值
     */
    public RoutePolicyBuilder<T> rule(String condition, T value) {
        if (condition == null || condition.trim().isEmpty()) {
            throw new IllegalArgumentException("Route condition cannot be empty");
        }
        this.rules.add(new RouteRule(condition, value));
        return this;
    }

    /**
     * 批量添加路由规则
     */
    public RoutePolicyBuilder<T> rules(List<RouteRule> rules) {
        if (rules != null) {
            this.rules.addAll(rules);
        }
        return this;
    }

    /**
     * 设置兜底返回值 (当所有规则都不匹配时返回)
     */
    public RoutePolicyBuilder<T> fallback(T fallbackValue) {
        this.fallbackValue = fallbackValue;
        return this;
    }

    /**
     * 设置扩展属性
     */
    public RoutePolicyBuilder<T> ext(String key, Object value) {
        this.ext.put(key, value);
        return this;
    }

    /**
     * 批量设置扩展属性
     */
    public RoutePolicyBuilder<T> ext(Map<String, Object> ext) {
        if (ext != null) {
            this.ext.putAll(ext);
        }
        return this;
    }

    /**
     * 构建最终的路由策略对象
     */
    public RoutePolicy build() {
        RoutePolicy policy = new RoutePolicy();
        policy.setId(this.id);
        policy.setType(this.type);
        // 使用新 List 防止外部修改
        policy.setRules(new ArrayList<>(this.rules));
        policy.setFallbackValue(this.fallbackValue);
        policy.getExt().putAll(this.ext);
        return policy;
    }
}
