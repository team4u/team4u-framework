package com.team4u.framework.router.core;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;

import java.lang.reflect.Type;

/**
 * 抽象路由器，处理通用的类型转换和兜底逻辑
 *
 * @author jay.wu
 */
public abstract class AbstractRouter implements Router {

    /**
     * 兜底路由值
     * 当所有规则都不匹配时，返回该值
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
     * 执行兜底逻辑
     * <p>
     * 使用策略中的显式兜底值，子类可直接调用此方法
     * </p>
     *
     * @param <T> 结果类型
     * @return 如果有兜底值则返回匹配结果，否则返回未匹配
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
