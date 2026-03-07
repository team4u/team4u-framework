package com.team4u.framework.retry.proxy;

import cn.hutool.crypto.digest.DigestUtil;
import com.team4u.framework.retry.RetryPayloadBuilder;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.backend.serialize.HutoolRetryTaskSnapshotSerializer;
import com.team4u.framework.retry.backend.serialize.RetryTaskSnapshotSerializer;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.config.DynamicRetryPolicyRegistry;
import com.team4u.framework.retry.policy.NamedRetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyRegistry;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 重试执行核心委托类
 * <p>
 * 提供与具体代理框架无关的重试执行逻辑，支持同步和异步方法的重试、
 * 持久化重试上下文的构建以及基于快照的任务恢复。
 */
public class RetryDelegate {

    /**
     * 参数序列化器，用于序列化方法调用参数
     */
    @Setter
    private RetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;

    /**
     * 任务快照序列化器，用于持久化重试任务快照
     */
    @Setter
    private RetryTaskSnapshotSerializer snapshotSerializer = HutoolRetryTaskSnapshotSerializer.INSTANCE;

    /**
     * 异步重试调度器
     */
    @Setter
    private ScheduledExecutorService scheduler;

    /**
     * 执行带有重试机制的方法调用
     *
     * @param method          正在执行的方法
     * @param target          目标对象实例
     * @param args            方法调用参数
     * @param retryable       重试配置注解
     * @param proceedTask     原始业务逻辑执行回调
     * @param backendSupplier 重试持久化后端供给者（若提供，则开启持久化重试模式）
     * @return 方法执行结果
     * @throws Throwable 最终执行失败时抛出的异常
     */
    public Object executeWithRetry(
            Method method,
            Object target,
            Object[] args,
            Retryable retryable,
            Callable<Object> proceedTask,
            Supplier<RetryBackend> backendSupplier) throws Throwable {

        // 若无重试注解或当前处于恢复执行上下文中，则直接执行业务逻辑，避免循环重试
        if (retryable == null || RecoveryExecutionContext.isRecovering()) {
            return proceedTask.call();
        }

        // 解析重试策略
        String policyKey = retryable.policy();
        RetryPolicy policy = Optional.ofNullable(DynamicRetryPolicyRegistry.getPolicy(policyKey))
                .orElseGet(() -> RetryPolicyRegistry.global().get(policyKey)
                        .map(NamedRetryPolicy::getPolicy)
                        .orElseThrow(() -> new IllegalArgumentException("未找到重试策略: " + policyKey)));

        // 获取重试后端，判断是否需要持久化
        RetryBackend backend = backendSupplier != null ? backendSupplier.get() : null;
        boolean persistent = backend != null;
        String taskType = resolveTaskType(method, retryable, persistent);
        boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());

        // 构建重试任务负载构造器（仅持久化模式需要）
        RetryPayloadBuilder payloadBuilder = createPayloadBuilder(method, target, args, taskType, policy, persistent);

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .build();

        if (isAsync) {
            return executeAsync(proceedTask, retryer, persistent, taskType, payloadBuilder);
        }
        return executeSync(proceedTask, retryer, persistent, taskType, payloadBuilder);
    }

    /**
     * 处理异步方法重试
     */
    private Object executeAsync(Callable<Object> proceedTask, Retryer retryer, boolean persistent,
            String taskType, RetryPayloadBuilder payloadBuilder) {
        ScheduledExecutorService executor = scheduler != null ? scheduler
                : RetryExecutorManager.global().getScheduler();
        if (!persistent) {
            return retryer.executeAsync(() -> invokeProceedTask(proceedTask), executor);
        }
        return retryer.executeAsync(taskType, payloadBuilder, () -> invokeProceedTask(proceedTask), executor);
    }

    /**
     * 处理同步方法重试
     */
    private Object executeSync(Callable<Object> proceedTask, Retryer retryer, boolean persistent,
            String taskType, RetryPayloadBuilder payloadBuilder) throws Throwable {
        if (!persistent) {
            try {
                return retryer.execute(proceedTask);
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        try {
            return retryer.execute(taskType, payloadBuilder, proceedTask);
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * 包装异步任务调用并处理异常
     */
    private CompletableFuture<Object> invokeProceedTask(Callable<Object> proceedTask) {
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> cf = (CompletableFuture<Object>) proceedTask.call();
            return cf;
        } catch (Throwable e) {
            CompletableFuture<Object> fail = new CompletableFuture<Object>();
            fail.completeExceptionally(e);
            return fail;
        }
    }

    /**
     * 解析任务类型，用于持久化恢复时定位处理器
     */
    private String resolveTaskType(Method method, Retryable retryable, boolean persistent) {
        String declaredTaskType = retryable.taskType();
        if (declaredTaskType != null && !declaredTaskType.trim().isEmpty()) {
            return declaredTaskType;
        }
        if (!persistent) {
            return method.getName();
        }
        return RetryTaskTypes.DEFAULT_PROXY_RECOVERY;
    }

    /**
     * 创建任务负载（Payload）构造器，负责在重试前冻结任务快照
     */
    private RetryPayloadBuilder createPayloadBuilder(Method method,
            Object target,
            Object[] args,
            String taskType,
            RetryPolicy policy,
            boolean persistent) {
        if (!persistent) {
            return null;
        }

        // 预先构建并冻结任务的基础信息（参数、类名、方法等）
        RetryTaskSnapshot frozenSnapshot = buildFrozenSnapshot(method, target, args, taskType, policy);
        return context -> snapshotSerializer
                .serialize(copySnapshotForAttempt(frozenSnapshot, context.getExecutedAttempts()));
    }

    /**
     * 构建初始任务快照
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

        // 序列化方法参数
        Object[] safeArgs = args != null ? args : new Object[0];
        Parameter[] parameters = method.getParameters();
        List<String> argJsonValues = new ArrayList<String>();
        for (int i = 0; i < parameters.length; i++) {
            Object argValue = i < safeArgs.length ? safeArgs[i] : null;
            argJsonValues.add(serializer.serialize(parameters[i], argValue));
        }
        snapshot.setArgJsonValues(Collections.unmodifiableList(argJsonValues));

        // 基于任务关键特征生成唯一的任务 ID，便于幂等性控制
        String idBase = taskType +
                "|" + snapshot.getBeanName() +
                "|" + snapshot.getMethodName() +
                "|" + snapshot.getArgJsonValues();
        snapshot.setTaskId("retry-" + DigestUtil.md5Hex(idBase));
        return snapshot;
    }

    /**
     * 为具体的一次重试尝试拷贝快照并更新状态
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
     * 解析目标 Bean 名称或类名
     */
    private String resolveBeanName(Method method, Object target) {
        if (target == null) {
            return method.getDeclaringClass().getName();
        }
        Class<?> targetClass = target.getClass();
        // 针对 CGLIB 等代理类，获取其真实父类名称
        if (targetClass.getName().contains("$$") && targetClass.getSuperclass() != null
                && targetClass.getSuperclass() != Object.class) {
            targetClass = targetClass.getSuperclass();
        }
        return targetClass.getName();
    }
}
