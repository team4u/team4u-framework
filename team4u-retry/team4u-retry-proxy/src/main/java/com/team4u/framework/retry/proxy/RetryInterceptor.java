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
 * 该拦截器负责识别目标方法或类上的 {@link Retryable} 注解，
 * 并委托给 {@link RetryDelegate} 执行具体的重试控制逻辑。
 */
@NoArgsConstructor
@AllArgsConstructor
public class RetryInterceptor implements MethodInterceptor {

    /**
     * 重试执行委托
     */
    private final RetryDelegate delegate = new RetryDelegate();

    /**
     * 重试持久化后端供给者
     */
    private Supplier<RetryBackend> backendSupplier;

    /**
     * 构造支持指定持久化后端的拦截器
     *
     * @param backend 重试持久化后端
     */
    public RetryInterceptor(RetryBackend backend) {
        this.backendSupplier = () -> backend;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method interfaceMethod = invocation.getMethod();
        Method effectiveMethod = interfaceMethod;

        // 若存在目标对象，尝试获取其真实实现的方法对象，以读取其上的注解
        if (invocation.getTarget() != null) {
            try {
                effectiveMethod = invocation.getTarget().getClass()
                        .getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                effectiveMethod = interfaceMethod;
            }
        }

        // 查找优先级：方法级别注解 > 真实方法级别注解 > 目标类级别注解
        Retryable retryable = interfaceMethod.getAnnotation(Retryable.class);
        if (retryable == null && effectiveMethod != interfaceMethod) {
            retryable = effectiveMethod.getAnnotation(Retryable.class);
        }
        if (retryable == null && invocation.getTarget() != null) {
            retryable = invocation.getTarget().getClass().getAnnotation(Retryable.class);
        }

        // 委托给 RetryDelegate 执行重试
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
