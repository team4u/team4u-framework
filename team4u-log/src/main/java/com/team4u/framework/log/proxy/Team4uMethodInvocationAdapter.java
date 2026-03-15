package com.team4u.framework.log.proxy;

import com.team4u.framework.proxy.core.MethodInvocation;

import java.lang.reflect.Method;

/**
 * team4u-proxy 方法调用适配器
 */
public class Team4uMethodInvocationAdapter implements LogInvocation {

    private final MethodInvocation invocation;

    public Team4uMethodInvocationAdapter(MethodInvocation invocation) {
        this.invocation = invocation;
    }

    @Override
    public Object getProxy() {
        return invocation.getProxy();
    }

    @Override
    public Object getTarget() {
        return invocation.getTarget();
    }

    @Override
    public Method getMethod() {
        return invocation.getMethod();
    }

    @Override
    public Object[] getArguments() {
        return invocation.getArguments();
    }

    @Override
    public Object proceed() throws Throwable {
        return invocation.proceed();
    }
}
