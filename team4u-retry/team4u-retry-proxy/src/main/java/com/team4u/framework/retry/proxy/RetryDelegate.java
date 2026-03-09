package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.client.InlineRetryClient;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.config.DynamicRetryPolicyRegistry;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RecoverySpec;
import com.team4u.framework.retry.domain.RetryTaskSpec;
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

        if (retryable == null || RecoveryExecutionContext.isRecovering()) {
            return proceedTask.call();
        }

        String policyKey = retryable.policy();
        RetryPolicy policy = Optional.ofNullable(DynamicRetryPolicyRegistry.getPolicy(policyKey))
                .orElseGet(() -> RetryPolicyFactoryRegistry.global().get(policyKey)
                        .map(RetryPolicyFactory::create)
                        .orElseThrow(() -> new IllegalArgumentException("Retry policy not found: " + policyKey)));

        if (retryable.mode() == RetryMode.INLINE || managedClient == null) {
            boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());
            if (isAsync) {
                ScheduledExecutorService executor = scheduler != null ? scheduler
                        : RetryExecutorManager.global().getScheduler();
                return inlineClient.executeAsync(policy, () -> invokeProceedTask(proceedTask), executor);
            } else {
                return inlineClient.execute(policy, proceedTask);
            }
        }

        // MANAGED 模式
        validateManagedMethod(method);
        InvocationRecoveryData recoveryData = buildRecoveryData(method, target, args);

        Class<? extends RecoveryHandler> recoveryClass = retryable.recovery();
        String taskType = resolveTaskType(retryable.recovery());

        RecoverySpec recoverySpec = RecoverySpec.of(taskType, recoveryData);

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
    private InvocationRecoveryData buildRecoveryData(Method method, Object target, Object[] args) {
        List<String> argTypes = new ArrayList<>();
        for (Class<?> paramType : method.getParameterTypes()) {
            argTypes.add(paramType.getName());
        }

        Object[] safeArgs = args != null ? args : new Object[0];
        Parameter[] parameters = method.getParameters();
        List<String> argValues = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            validateManagedSnapshotParameter(method, parameters[i]);
            Object argValue = i < safeArgs.length ? safeArgs[i] : null;
            argValues.add(serializer.serialize(parameters[i], argValue));
        }

        return InvocationRecoveryData.builder()
                .beanName(resolveBeanName(method, target))
                .methodName(method.getName())
                .argTypes(argTypes)
                .argValues(argValues)
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

    /**
     * 解析 Bean 名称。
     * <p>
     * 如果是代理对象，则解析其原始类名。
     *
     * @param method 目标方法
     * @param target 目标对象
     * @return 解析后的 Bean 名称或类名
     */
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

    private String resolveTaskType(Class<? extends RecoveryHandler> recoveryClass) {
        if (recoveryClass == null || recoveryClass == RecoveryHandler.class) {
            return InvocationReplay.TASK_NAME;
        }
        try {
            return recoveryClass.getDeclaredConstructor().newInstance().taskName();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to resolve taskType from recovery handler: " + recoveryClass.getName(), e);
        }
    }

    private String buildIdempotencyKey(InvocationRecoveryData recoveryData) {
        StringBuilder source = new StringBuilder();
        source.append(recoveryData.getBeanName()).append('#').append(recoveryData.getMethodName()).append('|');
        appendList(source, recoveryData.getArgTypes());
        source.append('|');
        appendList(source, recoveryData.getArgValues());
        return sha256(source.toString());
    }

    private void appendList(StringBuilder builder, List<String> values) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
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
