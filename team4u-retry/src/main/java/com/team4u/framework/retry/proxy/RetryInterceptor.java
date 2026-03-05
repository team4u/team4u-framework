package com.team4u.framework.retry.proxy;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.RetryDurability;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.config.DynamicRetryPolicyRegistry;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * 基于 team4u-proxy 的自动重试拦截器
 * <p>
 * 通过动态代理实现非侵入式的重试包装，替代传统的 Spring AOP 层。
 * 同步与异步方法均使用同步重试循环包装，保证代理调用链能正确复用。
 */
@NoArgsConstructor
@AllArgsConstructor
public class RetryInterceptor implements MethodInterceptor {

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread thread = new Thread(r);
                thread.setName("retry-interceptor-scheduler-" + thread.hashCode());
                thread.setDaemon(true);
                return thread;
            }
    );

    private RetryBackend backend;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Retryable retryable = invocation.getMethod().getAnnotation(Retryable.class);
        if (retryable == null) {
            return invocation.proceed();
        }

        String policyKey = retryable.value();
        RetryPolicy policy = Optional.ofNullable(DynamicRetryPolicyRegistry.getPolicy(policyKey))
                .orElseGet(() -> RetryPolicyRegistry.global().get(policyKey)
                        .map(NamedRetryPolicy::getPolicy)
                        .orElseThrow(() -> new IllegalArgumentException("未找到重试策略，Key: " + policyKey)));

        RetryDurability durability = RetryDurability.valueOf(retryable.durability().name());
        boolean isAsync = CompletableFuture.class.isAssignableFrom(invocation.getMethod().getReturnType());

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(durability)
                .build();

        if (isAsync) {
            return retryer.executeAsync(
                    policyKey,
                    () -> buildSnapshot(invocation).toJson(),
                    () -> {
                        try {
                            @SuppressWarnings("unchecked")
                            CompletableFuture<Object> cf = (CompletableFuture<Object>) invocation.proceed();
                            return cf;
                        } catch (Throwable e) {
                            CompletableFuture<Object> fail = new CompletableFuture<>();
                            fail.completeExceptionally(e);
                            return fail;
                        }
                    },
                    SCHEDULER);
        } else {
            return retryer.execute(
                    policyKey,
                    () -> buildSnapshot(invocation).toJson(),
                    () -> {
                        try {
                            return invocation.proceed();
                        } catch (Throwable t) {
                            if (t instanceof Exception) {
                                throw (Exception) t;
                            }
                            throw new RuntimeException(t);
                        }
                    });
        }
    }

    private RetryTaskSnapshot buildSnapshot(MethodInvocation invocation) {
        RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
        snapshot.setBeanName(invocation.getTarget().getClass().getName());
        snapshot.setMethodName(invocation.getMethod().getName());
        snapshot.setArgTypes(Arrays.stream(invocation.getMethod().getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.toList()));
        snapshot.setArgJsonValues(Arrays.stream(invocation.getArguments())
                .map(arg -> {
                    try {
                        return JSONUtil.toJsonStr(arg);
                    } catch (Exception e) {
                        return "Serialization failed: " + e.getMessage();
                    }
                })
                .collect(Collectors.toList()));
        return snapshot;
    }
}
