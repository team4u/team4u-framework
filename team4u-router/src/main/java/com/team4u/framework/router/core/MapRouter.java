package com.team4u.framework.router.core;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RuleTrace;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 映射路由器
 */
public class MapRouter extends AbstractRouter {

    private static final Log log = LogFactory.get();

    private final Map<String, Object> rules;
    private final Object fallbackValue;

    public MapRouter(RoutePolicy policy) {
        this.rules = policy.getRules().stream()
                .filter(rule -> rule.getCondition() != null)
                .collect(Collectors.toMap(RouteRule::getCondition,
                        rule -> rule.getValue() != null ? rule.getValue() : ""));
        this.fallbackValue = policy.getFallbackValue();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request) {
        // 请求对象为空时直接走兜底逻辑
        if (request == null) {
            return fallback();
        }

        // 尝试从规则库中精确匹配
        String routingKey = String.valueOf(request);
        Object target = rules.get(routingKey);

        if (target != null) {
            // 记录匹配命中的日志 (仅在 TRACE 级别)
            if (log.isTraceEnabled()) {
                log.trace("Route matched: key [{}] -> value [{}]", routingKey, target);
            }
            return RouteResult.matched((T) target, routingKey);
        }

        // 未匹配时记录日志 (仅在 TRACE 级别)
        if (log.isTraceEnabled()) {
            log.trace("Route unmatch: key [{}] not found, using fallback [{}]", routingKey, fallbackValue);
        }

        // 未匹配时走兜底逻辑
        return fallback();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> RouteTrace<T> trace(Object request) {
        long start = System.currentTimeMillis();
        RouteTrace<T> routeTrace = new RouteTrace<>();
        routeTrace.setRouterType("map");

        if (request == null) {
            routeTrace.setResult(fallback());
            routeTrace.setCostMs(System.currentTimeMillis() - start);
            return routeTrace;
        }

        String routingKey = String.valueOf(request);
        Object target = rules.get(routingKey);

        if (target != null) {
            routeTrace.addStep(RuleTrace.normal(routingKey, true, null));
            routeTrace.setResult(RouteResult.matched((T) target, routingKey));
        } else {
            routeTrace.addStep(RuleTrace.normal(routingKey, false, null));
            routeTrace.addStep(RuleTrace.fallback(fallbackValue != null));
            routeTrace.setResult(fallback());
        }

        routeTrace.setCostMs(System.currentTimeMillis() - start);
        return routeTrace;
    }

    /**
     * 执行兜底逻辑
     * 使用策略中的显式兜底值
     */
    @SuppressWarnings("unchecked")
    private <T> RouteResult<T> fallback() {
        return fallbackValue != null ? RouteResult.matched((T) fallbackValue) : RouteResult.unmatch();
    }
}
