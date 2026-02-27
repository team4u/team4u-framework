package com.team4u.framework.router.engine;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.router.api.AbstractRouter;
import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;

import java.util.Map;

/**
 * 表达式路由器
 */
public class ExpressionRouter extends AbstractRouter {

    private final Map<String, Object> rules;
    private final Criteria criteria;
    private final Object fallbackValue;

    public ExpressionRouter(RoutePolicy policy) {
        this(policy, null);
    }

    public ExpressionRouter(RoutePolicy policy, Criteria criteria) {
        this.rules = policy.getRules();
        this.criteria = criteria != null ? criteria : Criteria.global();
        this.fallbackValue = policy.getFallbackValue();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request) {
        // 构建匹配上下文，确保请求对象可用作表达式计算
        MatchContext context = (request instanceof MatchContext) ? (MatchContext) request : MatchContext.of(request);

        // 按顺序遍历所有路由规则进行匹配
        for (Map.Entry<String, Object> entry : rules.entrySet()) {
            String expr = entry.getKey();
            // 执行表达式匹配
            if (criteria.matches(expr, context)) {
                return RouteResult.matched((T) entry.getValue());
            }
        }

        // 所有规则未匹配时走兜底逻辑
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
