package com.team4u.framework.router.api.builder;

import com.team4u.framework.router.api.model.RoutePolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * 路由策略基础构建器
 * <p>
 * 采用递归泛型保证链式调用能够返回具体的子类类型，从而实现流畅的 API 调用。
 * </p>
 *
 * @param <T> 路由结果值的类型
 * @param <B> 子类构建器的类型
 * @author jay.wu
 */
@SuppressWarnings("unchecked")
public abstract class AbstractRoutePolicyBuilder<T, B extends AbstractRoutePolicyBuilder<T, B>> {

    protected final String type;
    protected final Map<String, Object> ext = new HashMap<>();
    protected String id;
    protected T fallbackValue;

    protected AbstractRoutePolicyBuilder(String type) {
        this.type = type;
    }

    /**
     * 设置路由唯一标识
     *
     * @param id 路由 ID
     * @return 当前构建器实例
     */
    public B id(String id) {
        this.id = id;
        return (B) this;
    }

    /**
     * 设置兜底返回值
     *
     * @param fallbackValue 兜底值
     * @return 当前构建器实例
     */
    public B fallback(T fallbackValue) {
        this.fallbackValue = fallbackValue;
        return (B) this;
    }

    /**
     * 添加扩展属性
     *
     * @param key   属性名
     * @param value 属性值
     * @return 当前构建器实例
     */
    public B ext(String key, Object value) {
        this.ext.put(key, value);
        return (B) this;
    }

    /**
     * 批量添加扩展属性
     *
     * @param ext 扩展属性映射
     * @return 当前构建器实例
     */
    public B ext(Map<String, Object> ext) {
        if (ext != null) {
            this.ext.putAll(ext);
        }
        return (B) this;
    }

    /**
     * 构建最终的路由策略对象
     *
     * @return 路由策略
     */
    public RoutePolicy build() {
        RoutePolicy policy = new RoutePolicy();
        policy.setId(this.id);
        policy.setType(this.type);
        policy.setFallbackValue(this.fallbackValue);
        policy.getExt().putAll(this.ext);

        // 由子类实现特定属性的装配逻辑
        doBuild(policy);
        return policy;
    }

    /**
     * 执行具体的构建逻辑，允许子类设置特定的属性（如 rules 或 delegates）
     *
     * @param policy 路由策略对象
     */
    protected abstract void doBuild(RoutePolicy policy);
}
