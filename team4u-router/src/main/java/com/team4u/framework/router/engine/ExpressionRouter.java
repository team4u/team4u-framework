package com.team4u.framework.router.engine;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.router.api.AbstractRouter;
import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import com.team4u.framework.router.api.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RuleTrace;

import java.util.List;

/**
 * 表达式路由器
 */
public class ExpressionRouter extends AbstractRouter {

    private static final Log log = LogFactory.get();

    private final List<RouteRule> rules;
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
        for (RouteRule rule : rules) {
            String expr = rule.getCondition();
            // 执行表达式匹配
            if (criteria.matches(expr, context)) {
                // 记录匹配命中的日志 (仅在 TRACE 级别)
                if (log.isTraceEnabled()) {
                    log.trace("Route matched: condition [{}] -> value [{}]", expr, rule.getValue());
                }
                return RouteResult.matched((T) rule.getValue(), expr);
            }
        }

        // 未匹配时记录日志 (仅在 TRACE 级别)
        if (log.isTraceEnabled()) {
            log.trace("Route fallback: no rules matched, using fallback [{}]", fallbackValue);
        }

        // 所有规则未匹配时走兜底逻辑
        return fallback();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> RouteTrace<T> trace(Object request) {
        long start = System.currentTimeMillis();
        RouteTrace<T> routeTrace = new RouteTrace<>();
        routeTrace.setRouterType("expression");

        MatchContext context = (request instanceof MatchContext) ? (MatchContext) request : MatchContext.of(request);

        for (RouteRule rule : rules) {
            String expr = rule.getCondition();

            // 核心：使用 criterion 的 trace 方法获取底层执行树
            com.team4u.framework.criterion.trace.TraceNode node = criteria.trace(expr, context);
            boolean isMatch = node.isMatched();

            // 将底层 TraceNode 挂载到路由轨迹中
            routeTrace.addStep(RuleTrace.normal(expr, isMatch, node.render()));

            if (isMatch) {
                routeTrace.setResult(RouteResult.matched((T) rule.getValue(), expr));
                routeTrace.setCostMs(System.currentTimeMillis() - start);
                return routeTrace;
            }
        }

        // 记录兜底轨迹
        boolean fallbackMatched = (fallbackValue != null);
        routeTrace.addStep(RuleTrace.fallback(fallbackMatched));
        routeTrace.setResult(this.fallback());
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
