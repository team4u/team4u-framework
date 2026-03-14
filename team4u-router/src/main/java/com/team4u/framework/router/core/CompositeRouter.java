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
 * 复合路由器（Waterfall Router）
 * <p>
 * 复合路由器允许将多个外部定义的子路由器（Delegates）组合在一起，形成路由链条。
 * 它的工作原理类似于瀑布流或责任链：
 * <ul>
 *   <li>按配置顺序逐个询问子路由器。</li>
 *   <li>如果子路由器返回“规则命中（Rule Match）”或“短路（Short Circuited）”，则立即停止并返回结果。</li>
 *   <li>结果合并逻辑：过程中收集的最末端有效的兜底值（Fallback Value）将作为整条链路的最终备选方案。</li>
 * </ul>
 * 这种设计增强了配置的复用性，允许将通用路由逻辑提取为独立单元，再根据业务场景进行编排。
 * </p>
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
                if (result.isRuleMatch() || result.isShortCircuited()) {
                    return result;
                }

                if (result.isFallbackMatch() && result.getValue() != null) {
                    fallback = result.getValue();
                }
            }
        }

        // 都不命中，返回兜底
        return fallback != null ? RouteResult.fallbackMatch((T) fallback) : RouteResult.unmatch();
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

            boolean childRealMatched = result != null && (result.isRuleMatch() || result.isShortCircuited());

            // 将子路由的追踪结果转化为一个普通的 Node 组装到当前父组合 Trace
            routeTrace.addStep(RuleTrace.normal(
                    delegateId,
                    childRealMatched, childTrace
            ));

            if (childRealMatched) {
                routeTrace.setResult(result);
                isMatch = true;
                break; // 命中截断
            }

            // 同步备选兜底
            if (result != null && result.isFallbackMatch() && result.getValue() != null) {
                fallback = result.getValue();
            }
        }

        if (!isMatch) {
            routeTrace.addStep(RuleTrace.fallback(fallback != null));
            routeTrace.setResult(fallback != null ? RouteResult.fallbackMatch((T) fallback) : RouteResult.unmatch());
        }

        return completeTrace(routeTrace, start);
    }
}
