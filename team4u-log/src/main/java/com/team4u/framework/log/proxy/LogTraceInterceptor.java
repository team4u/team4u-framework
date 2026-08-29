package com.team4u.framework.log.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;


/**
 * 自动日志追踪拦截器
 * <p>
 * 基于 team4u-proxy 实现，记录耗时、参数、返回值及异常信息。
 */
public class LogTraceInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        return LogTraceSupport.invoke(invocation, null);
    }
}
