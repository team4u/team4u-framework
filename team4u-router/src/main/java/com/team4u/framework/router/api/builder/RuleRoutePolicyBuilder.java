package com.team4u.framework.router.api.builder;

import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteRule;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则型路由策略构建器
 * <p>
 * 为 Map、Expression、Weight 等基于规则匹配的路由提供流式 API。
 * </p>
 *
 * @param <T> 路由结果值的类型
 * @author jay.wu
 */
public class RuleRoutePolicyBuilder<T> extends AbstractRoutePolicyBuilder<T, RuleRoutePolicyBuilder<T>> {

    private final List<RouteRule> rules = new ArrayList<>();

    public RuleRoutePolicyBuilder(String type) {
        super(type);
    }

    /**
     * 添加一条路由规则
     *
     * @param condition 匹配条件（Map 映射键或表达式）
     * @param value     命中后的返回值
     * @return 当前构建器实例
     */
    public RuleRoutePolicyBuilder<T> rule(String condition, T value) {
        if (condition == null || condition.trim().isEmpty()) {
            throw RouteConfigException.validationError("Route condition cannot be empty");
        }
        this.rules.add(new RouteRule(condition, value));
        return this;
    }

    /**
     * 设置路由规则列表
     *
     * @param rules 路由规则列表
     * @return 当前构建器实例
     */
    public RuleRoutePolicyBuilder<T> rules(List<RouteRule> rules) {
        if (rules != null) {
            this.rules.addAll(rules);
        }
        return this;
    }

    @Override
    protected void doBuild(RoutePolicy policy) {
        policy.setRules(new ArrayList<>(this.rules));
    }
}
