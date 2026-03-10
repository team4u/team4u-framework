package com.team4u.framework.retry.proxy;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 重试执行核心委托类
 */
public class RetryDelegate {

    private final InlineRetryClient inlineClient;
    private final ManagedRetryClient managedClient;
    @Setter
    private RetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;
    @Setter
    private ScheduledExecutorService scheduler;

    public RetryDelegate(InlineRetryClient inlineClient, ManagedRetryClient managedClient) {
        this.inlineClient = inlineClient;
        this.managedClient = managedClient;
    }

    public Object executeWithRetry(
            Method method,
            Object target,
            Object[] args,
            Retryable retryable,
            Callable<Object> proceedTask) throws Throwable {
        return executeWithRetry(method, method, method.getDeclaringClass(), target, args, retryable, proceedTask);
    }

    public Object executeWithRetry(
            Method invocationMethod,
            Method effectiveMethod,
            Class<?> recoveryTargetType,
            Object target,
            Object[] args,
            Retryable retryable,
            Callable<Object> proceedTask) throws Throwable {

        if (retryable == null || RecoveryExecutionContext.isRecovering()) {
            return proceedTask.call();
        }

        String policyKey = retryable.policy();
        RetryPolicy policy = Optional.ofNullable(DynamicRetryPolicyRegistry.getPolicy(policyKey))
                .orElseGet(() -> RetryPolicyFactoryRegistry.global().get(policyKey)
                        .map(RetryPolicyFactory::create)
                        .orElseThrow(() -> new IllegalArgumentException("Retry policy not found: " + policyKey)));

        if (retryable.mode() == RetryMode.INLINE || managedClient == null) {
            boolean isAsync = CompletableFuture.class.isAssignableFrom(effectiveMethod.getReturnType());
            if (isAsync) {
                ScheduledExecutorService executor = scheduler != null ? scheduler
                        : RetryExecutorManager.global().getScheduler();
                return inlineClient.executeAsync(policy, () -> invokeProceedTask(proceedTask), executor);
            } else {
                return inlineClient.execute(policy, proceedTask);
            }
        }

        // MANAGED 模式
        validateManagedMethod(effectiveMethod);
        InvocationRecoveryData recoveryData = buildRecoveryData(invocationMethod, effectiveMethod, recoveryTargetType, args);

        validateManagedRecovery(retryable.recovery());

        RecoverySpec recoverySpec = RecoverySpec.of(InvocationReplay.TASK_NAME, recoveryData);

        RetryTaskSpec<Object> taskSpec = RetryTaskSpec.builder()
                .idempotencyKey(buildIdempotencyKey(recoveryData))
                .policy(policy)
                .recovery(recoverySpec)
                .executor(proceedTask)
                .build();

        ManagedSubmitResult<Object> result = managedClient.submit(taskSpec);

        if (result instanceof ManagedSubmitResult.Completed) {
            return ((ManagedSubmitResult.Completed<Object>) result).getValue();
        } else if (result instanceof ManagedSubmitResult.Failed) {
            throw ((ManagedSubmitResult.Failed<?>) result).getError();
        } else if (result instanceof ManagedSubmitResult.Accepted) {
            return null;
        } else if (result instanceof ManagedSubmitResult.Rejected) {
            throw new IllegalStateException("Task rejected: " + ((ManagedSubmitResult.Rejected<?>) result).getReason());
        } else {
            throw new IllegalStateException("Unknown result: " + result);
        }
    }

    /**
     * 执行原始方法调用。
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
            CompletableFuture<Object> fail = new CompletableFuture<Object>();
            fail.completeExceptionally(e);
            return fail;
        }
    }

    /**
     * 构建恢复数据模型。
     *
     * @param method 目标方法
     * @param target 目标对象
     * @param args   原始参数
     * @return 包含调用上下文的恢复数据
     */
    private InvocationRecoveryData buildRecoveryData(
            Method invocationMethod,
            Method effectiveMethod,
            Class<?> recoveryTargetType,
            Object[] args) {
        Object[] safeArgs = args != null ? args : new Object[0];
        Parameter[] parameters = effectiveMethod.getParameters();
        List<InvocationArgSnapshot> snapshots = new ArrayList<InvocationArgSnapshot>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            validateManagedSnapshotParameter(effectiveMethod, parameters[i]);
            Object argValue = i < safeArgs.length ? safeArgs[i] : null;
            boolean ignored = parameters[i].isAnnotationPresent(RetryIgnore.class);
            snapshots.add(InvocationArgSnapshot.builder()
                    .typeName(parameters[i].getType().getName())
                    .serializedValue(ignored ? null : serializer.serialize(parameters[i], argValue))
                    .ignored(ignored)
                    .build());
        }

        return InvocationRecoveryData.builder()
                .targetTypeName(resolveTargetTypeName(invocationMethod, effectiveMethod, recoveryTargetType))
                .methodName(effectiveMethod.getName())
                .args(snapshots)
                .build();
    }

    private void validateManagedMethod(Method method) {
        if (method.getReturnType() != Void.TYPE) {
            throw new IllegalStateException(
                    "@Retryable(mode = MANAGED) only supports void return types in proxy/spring interception. "
                            + "Method: " + method.toGenericString()
                            + ". Use INLINE or ManagedRetryClient.submit(...) for result-bearing methods.");
        }
    }

    private void validateManagedSnapshotParameter(Method method, Parameter parameter) {
        if (parameter.isAnnotationPresent(RetryIgnore.class) && parameter.getType().isPrimitive()) {
            throw new IllegalStateException(
                    "@RetryIgnore cannot be used on primitive parameters in MANAGED mode. "
                            + "Method: " + method.toGenericString()
                            + ", Parameter: " + parameter.getName());
        }
    }

    private String resolveTargetTypeName(Method invocationMethod, Method effectiveMethod, Class<?> recoveryTargetType) {
        if (recoveryTargetType != null && recoveryTargetType != Object.class) {
            return recoveryTargetType.getName();
        }
        if (invocationMethod != null && invocationMethod.getDeclaringClass() != Object.class) {
            return invocationMethod.getDeclaringClass().getName();
        }
        return effectiveMethod.getDeclaringClass().getName();
    }

    private void validateManagedRecovery(Class<? extends RecoveryHandler> recoveryClass) {
        if (recoveryClass == null
                || recoveryClass == RecoveryHandler.class
                || recoveryClass == InvocationReplay.class) {
            return;
        }
        throw new IllegalStateException(
                "@Retryable(mode = MANAGED) only supports InvocationReplay in proxy/spring interception. "
                        + "Configured recovery: " + recoveryClass.getName()
                        + ". Use ManagedRetryClient.submit(...) for custom taskType/payload recovery.");
    }

    private String buildIdempotencyKey(InvocationRecoveryData recoveryData) {
        StringBuilder source = new StringBuilder();
        source.append(recoveryData.getTargetTypeName()).append('#').append(recoveryData.getMethodName()).append('|');
        appendSnapshots(source, recoveryData.getArgs());
        return sha256(source.toString());
    }

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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
