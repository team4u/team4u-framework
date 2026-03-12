package com.team4u.framework.retry.proxy;

import cn.hutool.core.lang.Assert;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.retry.inline.InlineRetryClient;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;

import java.lang.reflect.Method;

/**
 * 基于 team4u-proxy 实现的自动重试拦截器
 * <p>
 * 该拦截器负责识别目标方法或类上的 {@link Retryable} 注解，
 * 并委托给 {@link RetryDelegate} 执行具体的重试控制逻辑。
 */
public class RetryInterceptor implements MethodInterceptor {

    private final RetryDelegate delegate;

    public RetryInterceptor(InlineRetryClient inlineClient, ManagedRetryClient managedClient) {
        Assert.notNull(inlineClient, "InlineRetryClient must not be null");
        this.delegate = new RetryDelegate(inlineClient, managedClient);
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method interfaceMethod = invocation.getMethod();
        Class<?> targetClass = invocation.getTarget() == null ? null : invocation.getTarget().getClass();
        RetryMethodResolver.ResolvedRetryMethod resolved = RetryMethodResolver.resolve(interfaceMethod, targetClass);

        return delegate.executeWithRetry(
                interfaceMethod,
                resolved.getEffectiveMethod(),
                resolved.getRecoveryTargetType(),
                null,
                invocation.getArguments(),
                resolved.getRetryable(),
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
