package com.team4u.framework.router.core;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;

import java.lang.reflect.Type;

/**
 * 抽象路由器基类 (Abstract Router Base Class)
 * <p>
 * 为所有具体的路由器实现提供公共基础功能和约定，包括：
 * <ul>
 *   <li><b>结果类型转换</b>：底层路由逻辑通常返回 Object 类型，基类负责将其转换为调用方期望的泛型类型。</li>
 *   <li><b>兜底（Fallback）处理</b>：管理全局兜底逻辑，当所有规则均未命中时返回预设的默认值。</li>
 *   <li><b>追踪（Trace）辅助</b>：提供计时和结构化追踪日志的构建工具，便于子类实现诊断功能。</li>
 *   <li><b>统一路由接口</b>：实现 {@link Router} 接口，提供统一的路由入口。</li>
 * </ul>
 * 子类需要实现 {@link #route(Object)} 方法来定义具体的路由匹配逻辑。
 * </p>
 *
 * @author jay.wu
 */
public abstract class AbstractRouter implements Router {

    /**
     * 显式指定的兜底路由值。
     * 当该路由器内部的所有规则或子路由均未命中时，将尝试返回此值（以 Fallback 状态匹配）。
     */
    protected final Object fallbackValue;

    protected AbstractRouter(RoutePolicy policy) {
        this.fallbackValue = policy.getFallbackValue();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request, Type targetType) {
        // 调用子类的匹配逻辑
        RouteResult<?> result = route(request);
        if (result == null || result.isNotMatch()) {
            return RouteResult.unmatch();
        }

        Object rawValue = result.getValue();
        if (rawValue == null) {
            return rebuildResult(result, null);
        }

        if (targetType != null && !isInstance(targetType, rawValue)) {
            T convertedValue = ConvertUtil.convert(targetType, rawValue);
            return rebuildResult(result, convertedValue);
        }

        return (RouteResult<T>) result;
    }

    private <T> RouteResult<T> rebuildResult(RouteResult<?> original, T value) {
        if (original.isRuleMatch()) {
            return RouteResult.ruleMatch(value, original.getMatchedConditions());
        }
        if (original.isShortCircuited()) {
            return RouteResult.shortCircuited(value, original.getMatchedConditions());
        }
        if (original.isFallbackMatch()) {
            return RouteResult.fallbackMatch(value);
        }
        return RouteResult.unmatch();
    }

    private boolean isInstance(Type targetType, Object rawValue) {
        return targetType instanceof Class && ((Class<?>) targetType).isInstance(rawValue);
    }

    /**
     * 执行统一的兜底匹配逻辑。
     * <p>
     * 若配置了有效的兜底值，则返回 {@link RouteResult#fallbackMatch(Object)}；
     * 否则返回 {@link RouteResult#unmatch()}。
     * </p>
     *
     * @param <T> 结果类型
     * @return 路由结果
     */
    @SuppressWarnings("unchecked")
    protected <T> RouteResult<T> fallback() {
        return fallbackValue != null ? RouteResult.fallbackMatch((T) fallbackValue) : RouteResult.unmatch();
    }

    /**
     * 创建路由追踪对象
     * <p>
     * 子类在实现 {@link #trace(Object)} 方法时可以调用此方法创建基础追踪对象。
     * </p>
     *
     * @param routerType 路由器类型
     * @param <T>        结果类型
     * @return 新的追踪对象
     */
    protected <T> RouteTrace<T> createTrace(String routerType) {
        RouteTrace<T> trace = new RouteTrace<>();
        trace.setRouterType(routerType);
        return trace;
    }

    /**
     * 完成追踪并设置耗时
     *
     * @param trace 追踪对象
     * @param start 开始时间戳（毫秒）
     * @param <T>   结果类型
     * @return 完成的追踪对象
     */
    protected <T> RouteTrace<T> completeTrace(RouteTrace<T> trace, long start) {
        trace.setCostMs(System.currentTimeMillis() - start);
        return trace;
    }
}
