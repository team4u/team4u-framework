package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.retry.RetryBackend;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.function.Supplier;

/**
 * 基于 team4u-proxy 的自动重试拦截器
 * <p>
 * 通过动态代理实现非侵入式的重试包装，由 {@link RetryDelegate} 统一处理核心逻辑。
 */
@NoArgsConstructor
@AllArgsConstructor
public class RetryInterceptor implements MethodInterceptor {

    private Supplier<RetryBackend> backendSupplier;
    private final RetryDelegate delegate = new RetryDelegate();

    public RetryInterceptor(RetryBackend backend) {
        this.backendSupplier = () -> backend;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Retryable retryable = invocation.getMethod().getAnnotation(Retryable.class);
        return delegate.executeWithRetry(
                invocation.getMethod(),
                invocation.getTarget(),
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
