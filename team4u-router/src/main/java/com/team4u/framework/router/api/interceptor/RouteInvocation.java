package com.team4u.framework.router.api.interceptor;

import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.model.RouteResult;

import java.lang.reflect.Type;

/**
 * 路由执行调用上下文 (Route Invocation Context)
 * <p>
 * 该接口封装了路由执行过程中的所有状态信息，包括当前路由实例、请求对象、目标返回类型以及拦截器执行链。
 * 它是拦截器模式的核心，通过 {@link #proceed()} 驱动调用链向后移动。
 * </p>
 *
 * @param <T> 预期的路由结果类型
 */
public interface RouteInvocation<T> {

    /**
     * 获取当前路由实例
     */
    Router getRouter();

    /**
     * 获取当前路由策略 ID (如: router.order-router)
     */
    String getRouterId();

    /**
     * 获取原始/当前的请求对象
     */
    Object getRequest();

    /**
     * 替换或增强请求对象（允许拦截器修改参数传递给下一个节点）
     *
     * @param request 请求对象
     */
    void setRequest(Object request);

    /**
     * 获取目标转换类型
     */
    Class<T> getTargetType();

    /**
     * 获取目标泛型类型
     */
    Type getTargetGenericType();

    /**
     * 将请求传递给下一个拦截器；如果已经是最后一个，则执行真正的 Router 逻辑
     *
     * @return 路由结果
     */
    RouteResult<T> proceed();
}
