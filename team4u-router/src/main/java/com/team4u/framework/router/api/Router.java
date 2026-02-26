package com.team4u.framework.router.api;

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
}
