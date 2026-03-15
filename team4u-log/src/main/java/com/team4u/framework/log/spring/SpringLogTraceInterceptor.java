package com.team4u.framework.log.spring;


import com.team4u.framework.log.proxy.LogTraceSupport;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;

/**
 * Spring Bean 场景下的自动日志拦截器
 */
public class SpringLogTraceInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Class<?> targetClass = resolveTargetClass(invocation, method);
        return LogTraceSupport.invoke(new SpringMethodInvocationAdapter(invocation), targetClass);
    }

    private Class<?> resolveTargetClass(MethodInvocation invocation, Method method) {
        Object target = invocation.getThis();
        if (target != null) {
            return AopUtils.getTargetClass(target);
        }
        return method != null ? method.getDeclaringClass() : Object.class;
    }
}