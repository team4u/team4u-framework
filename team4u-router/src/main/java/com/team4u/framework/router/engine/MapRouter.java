package com.team4u.framework.router.engine;

import com.team4u.framework.router.api.AbstractRouter;
import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import com.team4u.framework.router.api.RouteRule;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 映射路由器
 */
public class MapRouter extends AbstractRouter {

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
        Object target = rules.get(String.valueOf(request));
        if (target != null) {
            return RouteResult.matched((T) target);
        }

        // 未匹配时走兜底逻辑
        return fallback();
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
