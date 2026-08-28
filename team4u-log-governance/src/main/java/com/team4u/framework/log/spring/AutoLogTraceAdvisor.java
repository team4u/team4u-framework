package com.team4u.framework.log.spring;

import com.team4u.framework.log.proxy.AutoLogTrace;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;

/**
 * 匹配 {@link AutoLogTrace} 的 Spring Advisor
 */
public class AutoLogTraceAdvisor extends AbstractBeanFactoryPointcutAdvisor {

    private final MethodInterceptor interceptor;

    public AutoLogTraceAdvisor(MethodInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Advice getAdvice() {
        return interceptor;
    }

    @Override
    public Pointcut getPointcut() {
        Pointcut classLevel = new AnnotationMatchingPointcut(AutoLogTrace.class, true);
        Pointcut methodLevel = new AnnotationMatchingPointcut(null, AutoLogTrace.class, true);
        return new ComposablePointcut(classLevel).union(methodLevel);
    }
}
