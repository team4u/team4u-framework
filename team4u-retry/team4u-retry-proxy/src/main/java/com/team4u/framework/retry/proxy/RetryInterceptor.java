package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.retry.backend.RetryBackend;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * 基于 team4u-proxy 实现的自动重试拦截器
 * <p>
 * 该拦截器负责识别目标方法上的 {@link Retryable} 注解，并利用 {@link RetryDelegate} 委托执行重试逻辑。
 */
@NoArgsConstructor
@AllArgsConstructor
public class RetryInterceptor implements MethodInterceptor {

    private final RetryDelegate delegate = new RetryDelegate();
    private Supplier<RetryBackend> backendSupplier;

    public RetryInterceptor(RetryBackend backend) {
        this.backendSupplier = () -> backend;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method interfaceMethod = invocation.getMethod();
        Method effectiveMethod = interfaceMethod;

        if (invocation.getTarget() != null) {
            try {
                effectiveMethod = invocation.getTarget().getClass()
                        .getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                effectiveMethod = interfaceMethod;
            }
        }

        Retryable retryable = interfaceMethod.getAnnotation(Retryable.class);
        if (retryable == null && effectiveMethod != interfaceMethod) {
            retryable = effectiveMethod.getAnnotation(Retryable.class);
        }
        if (retryable == null && invocation.getTarget() != null) {
            retryable = invocation.getTarget().getClass().getAnnotation(Retryable.class);
        }

        return delegate.executeWithRetry(
                effectiveMethod,
                invocation.getTarget(),
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
