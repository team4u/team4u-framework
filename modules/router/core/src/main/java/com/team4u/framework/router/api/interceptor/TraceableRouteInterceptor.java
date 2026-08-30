package com.team4u.framework.router.api.interceptor;

/**
 * 可选的 trace 观察型拦截器。
 * 不参与 trace 决策，仅补充诊断事件。
 */
public interface TraceableRouteInterceptor extends RouteInterceptor {

    default <T> Object beforeTrace(RouteTraceObservation<T> observation) {
        return null;
    }

    default <T> Object afterTrace(RouteTraceObservation<T> observation) {
        return null;
    }
}
