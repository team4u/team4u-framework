package com.team4u.framework.retry.proxy;

import cn.hutool.crypto.digest.DigestUtil;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.RetryDurability;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.backend.serialize.HutoolRetryTaskSnapshotSerializer;
import com.team4u.framework.retry.backend.serialize.RetryTaskSnapshotSerializer;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.config.DynamicRetryPolicyRegistry;
import com.team4u.framework.retry.proxy.serialize.HutoolRetryContextSerializer;
import com.team4u.framework.retry.proxy.serialize.RetryContextSerializer;
import lombok.Setter;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 重试执行核心委托类，供不同代理框架复用
 */
public class RetryDelegate {

    @Setter
    private RetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;

    @Setter
    private RetryTaskSnapshotSerializer snapshotSerializer = HutoolRetryTaskSnapshotSerializer.INSTANCE;

    @Setter
    private ScheduledExecutorService scheduler;

    /**
     * 核心执行逻辑
     */
    public Object executeWithRetry(
            Method method,
            Object target,
            Object[] args,
            Retryable retryable,
            Callable<Object> proceedTask,
            Supplier<RetryBackend> backendSupplier) throws Throwable {

        if (retryable == null) {
            return proceedTask.call();
        }

        String policyKey = retryable.value();
        RetryPolicy policy = Optional.ofNullable(DynamicRetryPolicyRegistry.getPolicy(policyKey))
                .orElseGet(() -> RetryPolicyRegistry.global().get(policyKey)
                        .map(NamedRetryPolicy::getPolicy)
                        .orElseThrow(() -> new IllegalArgumentException("Retry policy not found. Key: " + policyKey)));

        RetryDurability durability = RetryDurability.valueOf(retryable.durability().name());
        boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backendSupplier != null ? backendSupplier.get() : null)
                .durability(durability)
                .build();

        if (isAsync) {
            return retryer.executeAsync(
                    policyKey,
                    executedAttempts -> snapshotSerializer.serialize(
                            buildSnapshot(method, target, args, policyKey, policy, executedAttempts)),
                    () -> {
                        try {
                            @SuppressWarnings("unchecked")
                            CompletableFuture<Object> cf = (CompletableFuture<Object>) proceedTask.call();
                            return cf;
                        } catch (Throwable e) {
                            CompletableFuture<Object> fail = new CompletableFuture<>();
                            fail.completeExceptionally(e);
                            return fail;
                        }
                    },
                    scheduler != null ? scheduler : RetryExecutorManager.global().getScheduler());
        } else {
            try {
                return retryer.execute(
                        policyKey,
                        executedAttempts -> snapshotSerializer.serialize(
                                buildSnapshot(method, target, args, policyKey, policy, executedAttempts)),
                        proceedTask);
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    private RetryTaskSnapshot buildSnapshot(Method method,
                                            Object target,
                                            Object[] args,
                                            String policyKey,
                                            RetryPolicy policy,
                                            int executedAttempts) {
        RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
        snapshot.setTaskType(policyKey);
        snapshot.setExecutedAttempts(executedAttempts);
        snapshot.setMaxAttempts(policy.getMaxAttempts());

        snapshot.setBeanName(target.getClass().getName());
        snapshot.setMethodName(method.getName());
        snapshot.setArgTypes(Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.toList()));

        Parameter[] parameters = method.getParameters();
        List<String> argJsonValues = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            argJsonValues.add(serializer.serialize(parameters[i], args[i]));
        }
        snapshot.setArgJsonValues(argJsonValues);

        // 生成任务 ID：基于任务关键信息（不包含已执行次数和创建时间）计算 hash，确保同一个业务意图在重试过程中 ID 稳定
        String idBase = policyKey +
                "|" + snapshot.getBeanName() +
                "|" + snapshot.getMethodName() +
                "|" + snapshot.getArgJsonValues();
        snapshot.setTaskId("retry-" + DigestUtil.md5Hex(idBase));

        return snapshot;
    }
}
