package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.proxy.RetryDelegate;
import com.team4u.framework.retry.proxy.Retryable;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.function.Supplier;

/**
 * Spring AOP 体系下的重试拦截器
 */
public class SpringRetryInterceptor implements MethodInterceptor {

    private final RetryDelegate delegate = new RetryDelegate();
    private final Supplier<RetryBackend> backendSupplier;

    public SpringRetryInterceptor(Supplier<RetryBackend> backendSupplier) {
        this.backendSupplier = backendSupplier;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        // 兼容 Spring 的注解查找（支持找类上或接口上的注解）
        Retryable retryable = AnnotationUtils.findAnnotation(invocation.getMethod(), Retryable.class);
        if (retryable == null && invocation.getThis() != null) {
            retryable = AnnotationUtils.findAnnotation(invocation.getThis().getClass(), Retryable.class);
        }

        return delegate.executeWithRetry(
                invocation.getMethod(),
                invocation.getThis(),
                invocation.getArguments(),
                retryable,
                () -> {
                    try {
                        return invocation.proceed();
                    } catch (Exception e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                },
                backendSupplier);
    }
}
