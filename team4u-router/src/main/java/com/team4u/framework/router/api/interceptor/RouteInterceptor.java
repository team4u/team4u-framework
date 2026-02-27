package com.team4u.framework.router.api.interceptor;

import com.team4u.framework.policy.api.OrderedPolicy;
import com.team4u.framework.router.api.model.RouteResult;

/**
 * 路由拦截器接口
 * <p>
 * 利用 proceed() 方法，拦截器可以实现前置处理、后置处理、异常捕获甚至短路阻断。
 * </p>
 */
public interface RouteInterceptor extends OrderedPolicy {

    /**
     * 拦截路由执行
     *
     * @param invocation 路由执行链
     * @param <T>        路由结果类型
     * @return 路由结果
     */
    <T> RouteResult<T> intercept(RouteInvocation<T> invocation);

    /**
     * 定义拦截器执行顺序（越小优先级越高）
     */
    @Override
    default int priority() {
        return NORMAL;
    }
}
