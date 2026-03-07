package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.proxy.RetryDelegate;
import com.team4u.framework.retry.proxy.Retryable;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Spring AOP 体系下的重试拦截器实现。
 */
public class SpringRetryInterceptor implements MethodInterceptor {

    private final RetryDelegate delegate = new RetryDelegate();
    private final Supplier<RetryBackend> backendSupplier;

    public SpringRetryInterceptor(Supplier<RetryBackend> backendSupplier) {
        this.backendSupplier = backendSupplier;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Method specificMethod = method;

        if (target != null) {
            specificMethod = AopUtils.getMostSpecificMethod(method, target.getClass());
        }

        Retryable retryable = AnnotationUtils.findAnnotation(method, Retryable.class);
        if (retryable == null && specificMethod != method) {
            retryable = AnnotationUtils.findAnnotation(specificMethod, Retryable.class);
        }
        if (retryable == null && target != null) {
            retryable = AnnotationUtils.findAnnotation(target.getClass(), Retryable.class);
        }

        return delegate.executeWithRetry(
                specificMethod,
                target,
                invocation.getArguments(),
                retryable,
                () -> {
                    try {
                        return invocation.proceed();
                    } catch (Exception | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                },
                backendSupplier);
    }
}
