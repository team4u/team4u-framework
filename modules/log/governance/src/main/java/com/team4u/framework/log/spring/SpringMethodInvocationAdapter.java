package com.team4u.framework.log.spring;

import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.Method;

/**
 * Spring AOP 方法调用适配器
 * <p>
 * 将 Spring AOP 的 {@link MethodInvocation} 适配为 team4u-proxy 的
 * {@link com.team4u.framework.proxy.core.MethodInvocation}，
 * 供 {@link com.team4u.framework.log.proxy.LogTraceSupport} 统一消费。
 */
public class SpringMethodInvocationAdapter implements com.team4u.framework.proxy.core.MethodInvocation {

    private final MethodInvocation invocation;

    public SpringMethodInvocationAdapter(MethodInvocation invocation) {
        this.invocation = invocation;
    }

    @Override
    public Object getProxy() {
        return invocation.getThis();
    }

    @Override
    public Object getTarget() {
        return invocation.getThis();
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
