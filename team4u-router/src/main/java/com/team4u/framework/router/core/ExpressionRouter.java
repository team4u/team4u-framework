package com.team4u.framework.router.core;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.trace.TraceNode;
import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RuleTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 表达式路由器 (Expression-based Router)
 * <p>
 * 基于条件表达式进行匹配的路由器。它支持灵活的逻辑判断，能够根据请求对象的属性通过内置表达式引擎进行规则匹配。
 * 功能特性：
 * <ul>
 *   <li><b>规则排序匹配</b>：按照配置顺序依次计算规则表达式，首个匹配成功的规则即为结果（非多重匹配模式下）。</li>
 *   <li><b>多重匹配支持</b>：若开启 `multiMatch`，将返回所有匹配成功的规则结果列表。</li>
 *   <li><b>表达式预编译</b>：在初始化时预编译所有表达式，确保运行时的高性能执行。</li>
 * </ul>
 * </p>
 */
public class ExpressionRouter extends AbstractRouter {

    private static final Logger log = LoggerFactory.getLogger(ExpressionRouter.class);

    private final List<RouteRule> rules;
    private final Criteria criteria;
    private final boolean multiMatch;

    public ExpressionRouter(RoutePolicy policy) {
        this(policy, null);
    }

    public ExpressionRouter(RoutePolicy policy, Criteria criteria) {
        super(policy);
        this.rules = policy.getRules();
        this.criteria = criteria != null ? criteria : Criteria.global();
        this.multiMatch = policy.getExtProperty("multiMatch", false);

        // 初始化时预热表达式编译缓存，提升首次匹配性能
        if (rules != null) {
            for (RouteRule rule : rules) {
                if (rule.getCondition() != null) {
                    this.criteria.compileExpression(rule.getCondition());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request) {
        // 构建匹配上下文，确保请求对象可用作表达式计算
        MatchContext context = (request instanceof MatchContext) ? (MatchContext) request : MatchContext.of(request);

        List<Object> matchedValues = new ArrayList<>();
        List<String> matchedConditions = new ArrayList<>();

        // 按顺序遍历所有路由规则进行匹配
        for (RouteRule rule : rules) {
            String expr = rule.getCondition();
            // 执行表达式匹配
            if (criteria.matches(expr, context)) {
                if (!multiMatch) {
                    // 非多重匹配模式：记录匹配命中的日志 (仅在 TRACE 级别) 并立即返回
                    if (log.isTraceEnabled()) {
                        log.trace("Route matched: condition [{}] -> value [{}]", expr, rule.getValue());
                    }
                    return RouteResult.ruleMatch((T) rule.getValue(), expr);
                }

                // 记录匹配结果，继续循环
                matchedValues.add(rule.getValue());
                matchedConditions.add(expr);
            }
        }

        // 处理多重匹配结果
        if (!matchedValues.isEmpty()) {
            if (log.isTraceEnabled()) {
                log.trace("Route multi-matched: conditions [{}] -> values [{}]", matchedConditions, matchedValues);
            }
            // 泛型 T 在多重匹配下期望是 List 类型
            return RouteResult.ruleMatch((T) matchedValues, matchedConditions);
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
        RouteTrace<T> routeTrace = createTrace(RouterType.EXPRESSION);

        MatchContext context = (request instanceof MatchContext) ? (MatchContext) request : MatchContext.of(request);

        List<Object> matchedValues = new ArrayList<>();
        List<String> matchedConditions = new ArrayList<>();

        for (RouteRule rule : rules) {
            String expr = rule.getCondition();

            // 核心：使用 criterion 的 trace 方法获取底层执行树
            TraceNode node = criteria.trace(expr, context);
            boolean isMatch = node.isMatched();

            // 将底层 TraceNode 挂载到路由轨迹中
            routeTrace.addStep(RuleTrace.normal(expr, isMatch, node.render()));

            if (isMatch) {
                if (!multiMatch) {
                    routeTrace.setResult(RouteResult.ruleMatch((T) rule.getValue(), expr));
                    return completeTrace(routeTrace, start);
                }

                matchedValues.add(rule.getValue());
                matchedConditions.add(expr);
            }
        }

        if (!matchedValues.isEmpty()) {
            routeTrace.setResult(RouteResult.ruleMatch((T) matchedValues, matchedConditions));
        } else {
            // 记录兜底轨迹
            boolean fallbackMatched = (fallbackValue != null);
            routeTrace.addStep(RuleTrace.fallback(fallbackMatched));
            routeTrace.setResult(this.fallback());
        }

        return completeTrace(routeTrace, start);
    }
}
