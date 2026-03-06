package com.team4u.framework.retry.proxy;

import cn.hutool.crypto.digest.DigestUtil;
import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.retry.*;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.backend.serialize.HutoolRetryTaskSnapshotSerializer;
import com.team4u.framework.retry.backend.serialize.RetryTaskSnapshotSerializer;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.config.DynamicRetryPolicyRegistry;
import com.team4u.framework.retry.proxy.serialize.HutoolRetryContextSerializer;
import com.team4u.framework.retry.proxy.serialize.RetryContextSerializer;
import com.team4u.framework.retry.recovery.RecoveryExecutionContext;
import com.team4u.framework.retry.recovery.RetryTaskTypes;
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
 * 重试执行核心委托类，支持不同代理框架复用
 */
public class RetryDelegate {

    /**
     * 重试上下文序列化器，用于持久化方法参数
     */
    @Setter
    private RetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;

    /**
     * 重试任务快照序列化器，用于将任务状态存入后端存储
     */
    @Setter
    private RetryTaskSnapshotSerializer snapshotSerializer = HutoolRetryTaskSnapshotSerializer.INSTANCE;

    /**
     * 用于执行异步重试任务的任务调度器
     */
    @Setter
    private ScheduledExecutorService scheduler;

    /**
     * 执行带重试逻辑的任务
     *
     * @param method          当前调用的方法
     * @param target          目标业务对象
     * @param args            方法调用参数
     * @param retryable       重试注解配置
     * @param proceedTask     原始任务执行逻辑
     * @param backendSupplier 重试后端提供者
     * @return 任务执行结果
     * @throws Throwable 执行过程中的异常
     */
    public Object executeWithRetry(
            Method method,
            Object target,
            Object[] args,
            Retryable retryable,
            Callable<Object> proceedTask,
            Supplier<LeaseBackend> backendSupplier) throws Throwable {

        // 若未配置重试注解或当前处于恢复执行上下文中，则直接执行原始逻辑
        if (retryable == null || RecoveryExecutionContext.isRecovering()) {
            return proceedTask.call();
        }

        String policyKey = retryable.policy();
        RetryDurability durability = retryable.durability();
        String taskType = resolveTaskType(method, retryable, durability);

        // 获取重试策略，优先从动态注册表获取
        RetryPolicy policy = Optional.ofNullable(DynamicRetryPolicyRegistry.getPolicy(policyKey))
                .orElseGet(() -> RetryPolicyRegistry.global().get(policyKey)
                        .map(NamedRetryPolicy::getPolicy)
                        .orElseThrow(() -> new IllegalArgumentException("未找到重试策略: " + policyKey)));

        LeaseBackend backend = backendSupplier != null ? backendSupplier.get() : null;
        validateBackendIfNeeded(method, policyKey, durability, backend);

        boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());
        RetryPayloadBuilder payloadBuilder = createPayloadBuilder(method, target, args, taskType, policy, durability);

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(durability)
                .build();

        if (isAsync) {
            return executeAsync(proceedTask, retryer, durability, taskType, payloadBuilder);
        } else {
            return executeSync(proceedTask, retryer, durability, taskType, payloadBuilder);
        }
    }

    private Object executeAsync(Callable<Object> proceedTask, Retryer retryer, RetryDurability durability,
                                String taskType, RetryPayloadBuilder payloadBuilder) {
        ScheduledExecutorService executor = scheduler != null ? scheduler : RetryExecutorManager.global().getScheduler();
        if (durability == RetryDurability.MEMORY_ONLY) {
            return retryer.executeAsync(
                    () -> invokeProceedTask(proceedTask),
                    executor);
        }
        return retryer.executeAsync(
                taskType,
                payloadBuilder,
                () -> invokeProceedTask(proceedTask),
                executor);
    }

    private Object executeSync(Callable<Object> proceedTask, Retryer retryer, RetryDurability durability,
                               String taskType, RetryPayloadBuilder payloadBuilder) throws Throwable {
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

    private CompletableFuture<Object> invokeProceedTask(Callable<Object> proceedTask) {
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> cf = (CompletableFuture<Object>) proceedTask.call();
            return cf;
        } catch (Throwable e) {
            CompletableFuture<Object> fail = new CompletableFuture<>();
            fail.completeExceptionally(e);
            return fail;
        }
    }

    /**
     * 校验重试后端配置
     */
    private void validateBackendIfNeeded(Method method, String policyKey, RetryDurability durability,
                                         LeaseBackend backend) {
        if (durability == RetryDurability.MEMORY_ONLY || backend != null) {
            return;
        }
        String methodSignature = method != null ? method.toGenericString() : "<unknown-method>";
        throw new IllegalStateException(
                "当可靠性级别为 [" + durability + "] 时必须提供重试后端。方法: " + methodSignature + ", 策略: " + policyKey);
    }

    /**
     * 解析任务类型，若未显式指定则根据方法名或默认规则生成
     */
    private String resolveTaskType(Method method, Retryable retryable, RetryDurability durability) {
        String declaredTaskType = retryable.taskType();
        if (declaredTaskType != null && !declaredTaskType.trim().isEmpty()) {
            return declaredTaskType;
        }
        if (durability == RetryDurability.MEMORY_ONLY) {
            return method.getName();
        }
        return RetryTaskTypes.DEFAULT_PROXY_RECOVERY;
    }

    /**
     * 创建重试负载构建器，负责任务快照的序列化
     */
    private RetryPayloadBuilder createPayloadBuilder(Method method,
                                                     Object target,
                                                     Object[] args,
                                                     String taskType,
                                                     RetryPolicy policy,
                                                     RetryDurability durability) {
        if (durability == RetryDurability.MEMORY_ONLY) {
            return context -> {
                throw new IllegalStateException("内存重试模式下不应调用负载构建器");
            };
        }

        Supplier<RetryTaskSnapshot> snapshotSupplier;
        if (durability == RetryDurability.AT_LEAST_ONCE_DURABLE) {
            // 实时构建快照，确保数据一致性
            RetryTaskSnapshot frozenSnapshot = buildFrozenSnapshot(method, target, args, taskType, policy);
            snapshotSupplier = () -> frozenSnapshot;
        } else {
            // 延迟并缓存快照构建结果
            snapshotSupplier = memoizeSnapshot(() -> buildFrozenSnapshot(method, target, args, taskType, policy));
        }

        return context -> snapshotSerializer.serialize(
                copySnapshotForAttempt(snapshotSupplier.get(), context.getExecutedAttempts()));
    }

    /**
     * 实现快照的延迟加载与结果缓存
     */
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

    /**
     * 构建任务静态快照，包含方法签名、参数及重试配置
     */
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

        // 基于任务核心信息生成唯一标识，确保同一业务意图在重试过程中 ID 保持稳定
        String idBase = taskType +
                "|" + snapshot.getBeanName() +
                "|" + snapshot.getMethodName() +
                "|" + snapshot.getArgJsonValues();
        snapshot.setTaskId("retry-" + DigestUtil.md5Hex(idBase));

        return snapshot;
    }

    /**
     * 复制快照并更新执行次数
     */
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

    /**
     * 解析目标对象的 Bean 名称或类名
     */
    private String resolveBeanName(Method method, Object target) {
        if (target == null) {
            return method.getDeclaringClass().getName();
        }
        Class<?> targetClass = target.getClass();
        // 处理代理类，获取真实业务类名
        if (targetClass.getName().contains("$$") && targetClass.getSuperclass() != null
                && targetClass.getSuperclass() != Object.class) {
            targetClass = targetClass.getSuperclass();
        }
        return targetClass.getName();
    }
}
