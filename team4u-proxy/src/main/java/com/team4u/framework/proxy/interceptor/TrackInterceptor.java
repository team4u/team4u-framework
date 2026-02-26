package com.team4u.framework.proxy.interceptor;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.proxy.support.Tracker;
import lombok.RequiredArgsConstructor;

/**
 * 追踪拦截器：执行日志、耗时统计、审计等切面功能
 *
 * @author jay.wu
 */
@RequiredArgsConstructor
public class TrackInterceptor implements MethodInterceptor {

    private final Tracker tracker;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object proxy = invocation.getProxy();

        tracker.before(proxy, invocation.getMethod(), invocation.getArguments());

        try {
            // 继续执行后续拦截器或目标方法
            Object result = invocation.proceed();
            tracker.after(proxy, invocation.getMethod(), invocation.getArguments(), result);
            return result;
        } catch (Throwable e) {
            // 发生异常时触发异常钩子
            tracker.onException(proxy, invocation.getMethod(), invocation.getArguments(), e);
            throw e; // 异常必须向上抛出
        }
    }
}
