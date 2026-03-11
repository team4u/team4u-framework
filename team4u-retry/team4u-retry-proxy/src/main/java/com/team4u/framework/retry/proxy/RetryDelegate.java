package com.team4u.framework.retry.proxy;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.client.InlineRetryClient;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.config.DynamicRetryPolicyRegistry;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RecoverySpec;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.InvocationArgSnapshot;
import com.team4u.framework.retry.domain.store.InvocationRecoveryData;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;
import com.team4u.framework.retry.proxy.serialize.HutoolRetryContextSerializer;
import com.team4u.framework.retry.proxy.serialize.RetryContextSerializer;
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import com.team4u.framework.retry.recovery.RecoveryExecutionContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import lombok.Setter;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 重试执行核心委托类
 * <p>
 * 该类作为重试逻辑的执行入口，负责协调内联重试 (INLINE) 和托管重试 (MANAGED) 两种模式。
 * 它处理重试策略的解析、异步执行的调度、MANAGED 模式下的调用快照构建以及幂等键生成等核心逻辑。
 */
public class RetryDelegate {

    /**
     * 内联重试客户端，用于在当前进程内直接执行重试逻辑
     */
    private final InlineRetryClient inlineClient;

    /**
     * 托管重试客户端，用于将任务提交至中心化托管服务执行
     */
    private final ManagedRetryClient managedClient;

    /**
     * 重试上下文序列化器，用于在 MANAGED 模式下对方法调用参数进行快照
     */
    @Setter
    private RetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;

    /**
     * 调度执行器，用于处理异步重试任务的延迟执行
     */
    @Setter
    private ScheduledExecutorService scheduler;

    /**
     * 构造函数
     *
     * @param inlineClient  内联重试客户端
     * @param managedClient 托管重试客户端（可选）
     */
    public RetryDelegate(InlineRetryClient inlineClient, ManagedRetryClient managedClient) {
        this.inlineClient = inlineClient;
        this.managedClient = managedClient;
    }

    /**
     * 执行带有重试机制的方法调用
     *
     * @param method      被调用的原始方法
     * @param target      被调用的目标对象
     * @param args        调用参数
     * @param retryable   重试注解配置
     * @param proceedTask 原始业务逻辑任务
     * @return 执行结果
     * @throws Throwable 业务执行异常或重试失败异常
     */
    public Object executeWithRetry(
            Method method,
            Object target,
            Object[] args,
            Retryable retryable,
            Callable<Object> proceedTask) throws Throwable {
        return executeWithRetry(
                method,
                method,
                method.getDeclaringClass(),
                null,
                args,
                retryable,
                proceedTask);
    }

    /**
     * 执行带有重试机制的方法调用（详尽版本）
     *
     * @param invocationMethod   触发重试的入口方法（通常是接口方法）
     * @param effectiveMethod    实际带有注解或需要被恢复的方法（通常是实现类方法）
     * @param recoveryTargetType 恢复时的目标类型
     * @param args               当前调用参数
     * @param retryable          重试注解配置
     * @param proceedTask        业务执行任务
     * @return 执行结果
     * @throws Throwable 业务异常
     */
    public Object executeWithRetry(
            Method invocationMethod,
            Method effectiveMethod,
            Class<?> recoveryTargetType,
            String recoveryTargetBeanName,
            Object[] args,
            Retryable retryable,
            Callable<Object> proceedTask) throws Throwable {

        // 如果未配置重试，或者当前正处于恢复执行过程中，则直接执行业务逻辑，跳过重试控制
        if (retryable == null || RecoveryExecutionContext.isRecovering()) {
            return proceedTask.call();
        }

        // 解析重试策略，优先从动态注册中心获取，其次从全局工厂注册中心获取
        String policyKey = retryable.policy();
        RetryPolicy policy = Optional.ofNullable(DynamicRetryPolicyRegistry.getPolicy(policyKey))
                .orElseGet(() -> RetryPolicyFactoryRegistry.global().get(policyKey)
                        .map(RetryPolicyFactory::create)
                        .orElseThrow(() -> new IllegalArgumentException("Retry policy not found: " + policyKey)));

        if (retryable.mode() == RetryMode.MANAGED && managedClient == null) {
            throw new IllegalStateException(
                    "@Retryable(mode = MANAGED) requires ManagedRetryClient to be configured. "
                            + "Use INLINE or register a ManagedRetryClient bean/client before invoking "
                            + effectiveMethod.toGenericString());
        }

        // 处理 INLINE 模式
        if (retryable.mode() == RetryMode.INLINE) {
            // 识别是否为异步调用（返回类型为 CompletableFuture）
            boolean isAsync = CompletableFuture.class.isAssignableFrom(effectiveMethod.getReturnType());
            if (isAsync) {
                // 确定延迟任务执行器
                ScheduledExecutorService executor = scheduler != null ? scheduler
                        : RetryExecutorManager.global().getScheduler();
                return inlineClient.executeAsync(policy, () -> invokeProceedTask(proceedTask), executor);
            } else {
                return inlineClient.execute(policy, proceedTask);
            }
        }

        // 处理 MANAGED 模式
        // 验证托管模式下的方法有效性（如代理拦截目前仅支持 void 返回类型）
        validateManagedMethod(effectiveMethod);

        // 构建调用快照数据，用于持久化和后续恢复
        InvocationRecoveryData recoveryData = buildRecoveryData(
                invocationMethod,
                effectiveMethod,
                recoveryTargetType,
                recoveryTargetBeanName,
                args);

        // 验证托管模式下的恢复处理器配置
        validateManagedRecovery(retryable.recovery());

        // 使用通用调用回放处理器构建恢复规范
        RecoverySpec recoverySpec = RecoverySpec.of(InvocationReplay.TASK_NAME, JSONUtil.toJsonStr(recoveryData));

        // 构建托管任务 definition
        RetryTaskSpec<Object> taskSpec = RetryTaskSpec.builder()
                .idempotencyKey(buildIdempotencyKey(recoveryData))
                .policy(policy)
                .recovery(recoverySpec)
                .executor(proceedTask)
                .build();

        // 提交至托管重试服务
        ManagedSubmitResult<Object> result = managedClient.submit(taskSpec);

        // 根据提交结果进行相应的分发处理
        if (result instanceof ManagedSubmitResult.Completed) {
            return ((ManagedSubmitResult.Completed<Object>) result).getValue();
        } else if (result instanceof ManagedSubmitResult.Failed) {
            throw ((ManagedSubmitResult.Failed<?>) result).getError();
        } else if (result instanceof ManagedSubmitResult.Accepted) {
            // 任务已被接受进入异步执行队列，代理层返回 null
            return null;
        } else if (result instanceof ManagedSubmitResult.Existing) {
            // MANAGED 代理当前只支持 void 返回值，命中幂等记录时也无需向调用方暴露值。
            return null;
        } else if (result instanceof ManagedSubmitResult.Rejected) {
            throw new IllegalStateException("Task rejected: " + ((ManagedSubmitResult.Rejected<?>) result).getReason());
        } else {
            throw new IllegalStateException("Unknown result: " + result);
        }
    }

    /**
     * 调用原始业务任务，确保异步场景下的异常正确传递
     *
     * @param proceedTask 原始调用任务
     * @return 执行结果的 CompletableFuture
     */
    private CompletableFuture<Object> invokeProceedTask(Callable<Object> proceedTask) {
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> cf = (CompletableFuture<Object>) proceedTask.call();
            return cf;
        } catch (Throwable e) {
            // 捕获任务启动时的同步异常，并转化为已完成的异常 Future
            CompletableFuture<Object> fail = new CompletableFuture<>();
            fail.completeExceptionally(e);
            return fail;
        }
    }

    /**
     * 构建调用现场的恢复数据快照
     * <p>
     * 该方法会遍历方法参数，识别 {@link RetryIgnore} 注解，并利用序列化器对有效参数进行持久化建模。
     *
     * @param invocationMethod   入口方法
     * @param effectiveMethod    实际方法
     * @param recoveryTargetType 恢复目标类
     * @param args               原始入参
     * @return 包含目标类、方法名及参数快照的恢复数据
     */
    private InvocationRecoveryData buildRecoveryData(
            Method invocationMethod,
            Method effectiveMethod,
            Class<?> recoveryTargetType,
            String recoveryTargetBeanName,
            Object[] args) {
        Object[] safeArgs = args != null ? args : new Object[0];
        Parameter[] parameters = effectiveMethod.getParameters();
        List<InvocationArgSnapshot> snapshots = new ArrayList<>(parameters.length);

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            validateManagedSnapshotParameter(effectiveMethod, parameter);

            Object argValue = i < safeArgs.length ? safeArgs[i] : null;
            boolean ignored = parameter.isAnnotationPresent(RetryIgnore.class);

            // 构建参数快照，对于被忽略的参数不进行序列化
            snapshots.add(InvocationArgSnapshot.builder()
                    .typeName(parameter.getType().getName())
                    .serializedValue(ignored ? null : serializer.serialize(parameter, argValue))
                    .ignored(ignored)
                    .build());
        }

        return InvocationRecoveryData.builder()
                .targetTypeName(resolveTargetTypeName(invocationMethod, effectiveMethod, recoveryTargetType))
                .targetBeanName(recoveryTargetBeanName)
                .methodName(effectiveMethod.getName())
                .args(snapshots)
                .build();
    }

    /**
     * 校验被拦截方法是否符合托管模式的要求
     */
    private void validateManagedMethod(Method method) {
        if (method.getReturnType() != Void.TYPE) {
            // 在代理拦截场景下，托管模式目前仅支持无返回值方法，因为异步恢复无法同步返回结果
            throw new IllegalStateException(
                    "@Retryable(mode = MANAGED) only supports void return types in proxy/spring interception. "
                            + "Method: " + method.toGenericString()
                            + ". Use INLINE or ManagedRetryClient.submit(...) for result-bearing methods.");
        }
    }

    /**
     * 校验参数快照规则
     */
    private void validateManagedSnapshotParameter(Method method, Parameter parameter) {
        if (parameter.isAnnotationPresent(RetryIgnore.class) && parameter.getType().isPrimitive()) {
            // 基本类型无法在恢复时被设置为 null，因此不允许标记为 @RetryIgnore
            throw new IllegalStateException(
                    "@RetryIgnore cannot be used on primitive parameters in MANAGED mode. "
                            + "Method: " + method.toGenericString()
                            + ", Parameter: " + parameter.getName());
        }
    }

    /**
     * 确定恢复时需要使用的目标类全限定名
     */
    private String resolveTargetTypeName(Method invocationMethod, Method effectiveMethod, Class<?> recoveryTargetType) {
        if (recoveryTargetType != null && recoveryTargetType != Object.class) {
            return recoveryTargetType.getName();
        }
        if (invocationMethod != null && invocationMethod.getDeclaringClass() != Object.class) {
            return invocationMethod.getDeclaringClass().getName();
        }
        return effectiveMethod.getDeclaringClass().getName();
    }

    /**
     * 校验恢复处理器配置
     */
    @SuppressWarnings("rawtypes")
    private void validateManagedRecovery(Class<? extends RecoveryHandler> recoveryClass) {
        if (recoveryClass == null
                || recoveryClass == RecoveryHandler.class
                || recoveryClass == InvocationReplay.class) {
            return;
        }
        // 代理拦截自动生成的任务固定使用 InvocationReplay 进行回放，不允许自定义
        throw new IllegalStateException(
                "@Retryable(mode = MANAGED) only supports InvocationReplay in proxy/spring interception. "
                        + "Configured recovery: " + recoveryClass.getName()
                        + ". Use ManagedRetryClient.submit(...) for custom taskType/payload recovery.");
    }

    /**
     * 构建幂等键
     * <p>
     * 通过目标类名、方法名以及参数快照的内容生成 SHA-256 哈希值，用于确保同一业务调用不会产生重复的托管任务。
     */
    private String buildIdempotencyKey(InvocationRecoveryData recoveryData) {
        StringBuilder source = new StringBuilder();
        source.append(recoveryData.getTargetTypeName()).append('#').append(recoveryData.getMethodName()).append('|');
        appendSnapshots(source, recoveryData.getArgs());
        return sha256(source.toString());
    }

    /**
     * 将参数快照序列化为字符串追加至幂等源中
     */
    private void appendSnapshots(StringBuilder builder, List<InvocationArgSnapshot> snapshots) {
        if (snapshots == null) {
            return;
        }
        for (int i = 0; i < snapshots.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            InvocationArgSnapshot snapshot = snapshots.get(i);
            builder.append(snapshot == null ? "null" : snapshot.getTypeName())
                    .append(':')
                    .append(snapshot != null && snapshot.isIgnored())
                    .append(':')
                    .append(snapshot == null ? null : snapshot.getSerializedValue());
        }
    }

    /**
     * 生成 SHA-256 摘要字符串
     */
    private String sha256(String value) {
        return DigestUtil.sha256Hex(value);
    }
}
