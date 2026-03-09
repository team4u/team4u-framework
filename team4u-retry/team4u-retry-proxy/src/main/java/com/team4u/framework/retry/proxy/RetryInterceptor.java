package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.retry.client.InlineRetryClient;
import com.team4u.framework.retry.client.ManagedRetryClient;
import lombok.NoArgsConstructor;

import java.lang.reflect.Method;

/**
 * 基于 team4u-proxy 实现的自动重试拦截器
 * <p>
 * 该拦截器负责识别目标方法或类上的 {@link Retryable} 注解，
 * 并委托给 {@link RetryDelegate} 执行具体的重试控制逻辑。
 */
@NoArgsConstructor
public class RetryInterceptor implements MethodInterceptor {

    private RetryDelegate delegate;

    public RetryInterceptor(InlineRetryClient inlineClient, ManagedRetryClient managedClient) {
        this.delegate = new RetryDelegate(inlineClient, managedClient);
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

        if (delegate == null) {
            throw new IllegalStateException("RetryDelegate is not initialized with clients");
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
                });
    }
}
