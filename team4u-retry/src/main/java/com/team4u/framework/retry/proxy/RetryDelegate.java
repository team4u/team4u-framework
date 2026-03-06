package com.team4u.framework.retry.proxy;

import cn.hutool.crypto.digest.DigestUtil;
import com.team4u.framework.retry.*;
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
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
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

        String policyKey = retryable.policy();
        String taskType = retryable.taskType().isEmpty() ? method.getName() : retryable.taskType();
        RetryPolicy policy = Optional.ofNullable(DynamicRetryPolicyRegistry.getPolicy(policyKey))
                .orElseGet(() -> RetryPolicyRegistry.global().get(policyKey)
                        .map(NamedRetryPolicy::getPolicy)
                        .orElseThrow(() -> new IllegalArgumentException("Retry policy not found. Key: " + policyKey)));

        RetryDurability durability = retryable.durability();
        RetryBackend backend = backendSupplier != null ? backendSupplier.get() : null;
        validateBackendIfNeeded(method, policyKey, durability, backend);
        boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());
        RetryPayloadBuilder payloadBuilder = createPayloadBuilder(method, target, args, taskType, policy, durability);

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(durability)
                .build();

        if (isAsync) {
            if (durability == RetryDurability.MEMORY_ONLY) {
                return retryer.executeAsync(
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
            }
            return retryer.executeAsync(
                    taskType,
                    payloadBuilder,
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
            if (durability == RetryDurability.MEMORY_ONLY) {
                try {
                    return retryer.execute(proceedTask);
                } catch (Exception | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
            try {
                return retryer.execute(
                        taskType,
                        payloadBuilder,
                        proceedTask);
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    private void validateBackendIfNeeded(Method method, String policyKey, RetryDurability durability,
                                         RetryBackend backend) {
        if (durability == RetryDurability.MEMORY_ONLY || backend != null) {
            return;
        }
        String methodSignature = method != null ? method.toGenericString() : "<unknown-method>";
        throw new IllegalStateException(
                "Retry backend is required when durability is [" + durability + "], but none was provided. " +
                        "method=" + methodSignature + ", policyKey=" + policyKey);
    }

    private RetryPayloadBuilder createPayloadBuilder(Method method,
                                                     Object target,
                                                     Object[] args,
                                                     String taskType,
                                                     RetryPolicy policy,
                                                     RetryDurability durability) {
        if (durability == RetryDurability.MEMORY_ONLY) {
            return context -> {
                throw new IllegalStateException("Payload builder must not be used for MEMORY_ONLY retry methods.");
            };
        }

        Supplier<RetryTaskSnapshot> snapshotSupplier;
        if (durability == RetryDurability.AT_LEAST_ONCE_DURABLE) {
            RetryTaskSnapshot frozenSnapshot = buildFrozenSnapshot(method, target, args, taskType, policy);
            snapshotSupplier = () -> frozenSnapshot;
        } else {
            snapshotSupplier = memoizeSnapshot(() -> buildFrozenSnapshot(method, target, args, taskType, policy));
        }

        return context -> snapshotSerializer.serialize(
                copySnapshotForAttempt(snapshotSupplier.get(), context.getExecutedAttempts()));
    }

    private Supplier<RetryTaskSnapshot> memoizeSnapshot(Supplier<RetryTaskSnapshot> snapshotBuilder) {
        AtomicReference<RetryTaskSnapshot> cachedSnapshot = new AtomicReference<>();
        Object monitor = new Object();
        return () -> {
            RetryTaskSnapshot snapshot = cachedSnapshot.get();
            if (snapshot != null) {
                return snapshot;
            }
            synchronized (monitor) {
                snapshot = cachedSnapshot.get();
                if (snapshot == null) {
                    snapshot = snapshotBuilder.get();
                    cachedSnapshot.set(snapshot);
                }
                return snapshot;
            }
        };
    }

    private RetryTaskSnapshot buildFrozenSnapshot(Method method,
                                                  Object target,
                                                  Object[] args,
                                                  String taskType,
                                                  RetryPolicy policy) {
        RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
        snapshot.setTaskType(taskType);
        snapshot.setMaxAttempts(policy.getMaxAttempts());

        snapshot.setBeanName(resolveBeanName(method, target));
        snapshot.setMethodName(method.getName());
        snapshot.setArgTypes(Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList)));

        Object[] safeArgs = args != null ? args : new Object[0];
        Parameter[] parameters = method.getParameters();
        List<String> argJsonValues = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            Object argValue = i < safeArgs.length ? safeArgs[i] : null;
            argJsonValues.add(serializer.serialize(parameters[i], argValue));
        }
        snapshot.setArgJsonValues(Collections.unmodifiableList(argJsonValues));

        // 生成任务 ID：基于任务关键信息（不包含已执行次数和创建时间）计算 hash，确保同一个业务意图在重试过程中 ID 稳定
        String idBase = taskType +
                "|" + snapshot.getBeanName() +
                "|" + snapshot.getMethodName() +
                "|" + snapshot.getArgJsonValues();
        snapshot.setTaskId("retry-" + DigestUtil.md5Hex(idBase));

        return snapshot;
    }

    private RetryTaskSnapshot copySnapshotForAttempt(RetryTaskSnapshot frozenSnapshot, int executedAttempts) {
        RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
        snapshot.setTaskId(frozenSnapshot.getTaskId());
        snapshot.setTaskType(frozenSnapshot.getTaskType());
        snapshot.setExecutedAttempts(executedAttempts);
        snapshot.setMaxAttempts(frozenSnapshot.getMaxAttempts());
        snapshot.setCreatedAt(frozenSnapshot.getCreatedAt());
        snapshot.setBeanName(frozenSnapshot.getBeanName());
        snapshot.setMethodName(frozenSnapshot.getMethodName());
        snapshot.setArgTypes(frozenSnapshot.getArgTypes());
        snapshot.setArgJsonValues(frozenSnapshot.getArgJsonValues());
        return snapshot;
    }

    private String resolveBeanName(Method method, Object target) {
        if (target == null) {
            return method.getDeclaringClass().getName();
        }
        Class<?> targetClass = target.getClass();
        if (targetClass.getName().contains("$$") && targetClass.getSuperclass() != null
                && targetClass.getSuperclass() != Object.class) {
            targetClass = targetClass.getSuperclass();
        }
        return targetClass.getName();
    }
}
