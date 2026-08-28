package com.team4u.framework.log.spring;

import com.team4u.framework.log.proxy.LogInvocation;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.Method;

/**
 * Spring AOP 方法调用适配器
 */
public class SpringMethodInvocationAdapter implements LogInvocation {

    private final MethodInvocation invocation;

    public SpringMethodInvocationAdapter(MethodInvocation invocation) {
        this.invocation = invocation;
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
