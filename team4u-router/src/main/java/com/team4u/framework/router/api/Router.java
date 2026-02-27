package com.team4u.framework.router.api;

import com.team4u.framework.router.api.trace.RouteTrace;

/**
 * 核心路由接口
 */
public interface Router {

    /**
     * 执行路由
     *
     * @param request 路由请求对象
     * @param <T>     结果类型
     * @return 路由结果
     */
    <T> RouteResult<T> route(Object request);

    /**
     * 执行路由并转换结果类型
     *
     * @param request    路由请求对象
     * @param targetType 期望转换的目标类型
     * @param <T>        结果类型
     * @return 路由结果
     */
    <T> RouteResult<T> route(Object request, Class<T> targetType);

    /**
     * 执行路由并返回诊断轨迹
     *
     * @param request 路由请求对象
     * @param <T>     结果类型
     * @return 路由诊断轨迹
     */
    default <T> RouteTrace<T> trace(Object request) {
        long start = System.currentTimeMillis();
        RouteTrace<T> trace = new RouteTrace<>();
        trace.setResult(route(request));
        trace.setCostMs(System.currentTimeMillis() - start);
        return trace;
    }
}
