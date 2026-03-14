package com.team4u.framework.router.api.interceptor;

import com.team4u.framework.policy.api.OrderedPolicy;
import com.team4u.framework.router.api.model.RouteResult;

/**
 * 路由拦截器接口
 * <p>
 * 拦截器提供了一种在路由执行前后注入横切关注逻辑的机制。
 * 通过 {@link RouteInvocation#proceed()} 方法，拦截器可以控制请求的流转，实现包括但不限于以下功能：
 * <ul>
 *   <li><b>鉴权与校验</b>：路由执行前检查请求合法性性能。</li>
 *   <li><b>动态改写</b>：在请求进入路由器前修改请求参数。</li>
 *   <li><b>结果处理</b>：对路由结果进行包装、转换或缓存。</li>
 *   <li><b>故障处理</b>：捕获后续阶段抛出的异常并提供降级策略。</li>
 * </ul>
 * </p>
 */
public interface RouteInterceptor extends OrderedPolicy {

    /**
     * 拦截路由执行
     *
     * @param invocation 路由执行链上下文
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

