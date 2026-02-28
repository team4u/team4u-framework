package com.team4u.framework.router.core;

import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RuleTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * 复合路由器
 * <p>
 * 将多个独立配置的路由按照 delegates 顺序串行执行。
 * 命中即截断返回。若未命中，则返回包含实际兜底值的匹配对象，或者继续返回未匹配。
 */
public class CompositeRouter extends AbstractRouter {

    private final List<String> delegates;
    private final RoutingManager manager;

    public CompositeRouter(RoutePolicy policy, RoutingManager manager) {
        super(policy);
        this.manager = manager;
        this.delegates = policy.getExtProperty("delegates", new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request) {
        Object fallback = this.fallbackValue;

        // 依次委托给子 Router 执行
        for (String delegateId : delegates) {
            RouteResult<T> result = manager.route(delegateId, request);

            if (result != null && result.isMatch()) {
                // 如果是真实命中（存在命中条件），则直接截断返回
                if (result.getMatchedConditions() != null) {
                    return result;
                }

                // 走到这里说明子路由产生了 fallback（isMatch=true 但 condition=null）
                if (result.getValue() != null) {
                    fallback = result.getValue();
                }
            }
        }

        // 都不命中，返回兜底
        return fallback != null ? RouteResult.matched((T) fallback) : RouteResult.unmatch();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteTrace<T> trace(Object request) {
        long start = System.currentTimeMillis();
        // 此处标识为 COMPOSITE 组合路由以供排查时观察
        RouteTrace<T> routeTrace = createTrace(RouterType.COMPOSITE);

        Object fallback = this.fallbackValue;
        boolean isMatch = false;

        // 依次委托给子 Router 追踪执行
        for (String delegateId : delegates) {
            // 调用 Manager 获取子路由的内部追踪对象
            RouteTrace<T> childTrace = manager.trace(delegateId, request);
            RouteResult<T> result = childTrace != null ? childTrace.getResult() : null;

            boolean childRealMatched = result != null && result.isMatch() && result.getMatchedConditions() != null;

            // 将子路由的追踪结果转化为一个普通的 Node 组装到当前父组合 Trace
            routeTrace.addStep(RuleTrace.normal(
                    "Delegate -> " + delegateId,
                    childRealMatched, childTrace
            ));

            if (childRealMatched) {
                routeTrace.setResult(result);
                isMatch = true;
                break; // 命中截断
            }

            // 同步备选兜底
            if (result != null && result.isMatch() && result.getValue() != null) {
                fallback = result.getValue();
            }
        }

        if (!isMatch) {
            routeTrace.addStep(RuleTrace.fallback(fallback != null));
            routeTrace.setResult(fallback != null ? RouteResult.matched((T) fallback) : RouteResult.unmatch());
        }

        return completeTrace(routeTrace, start);
    }
}