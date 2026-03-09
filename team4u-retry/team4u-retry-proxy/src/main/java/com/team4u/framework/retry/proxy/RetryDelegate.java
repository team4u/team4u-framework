package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.RetryMode;
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
 */
public class RetryDelegate {

    @Setter
    private RetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;

    @Setter
    private ScheduledExecutorService scheduler;

    private final InlineRetryClient inlineClient;
    private final ManagedRetryClient managedClient;

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
                        .orElseThrow(() -> new IllegalArgumentException("未找到重试策略: " + policyKey)));

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
        InvocationRecoveryData recoveryData = buildRecoveryData(method, target, args);

        Class<? extends RecoveryHandler> recoveryClass = retryable.recovery();
        String targetTaskName = InvocationReplay.TASK_NAME; // 默认使用通用放音机
        if (recoveryClass != null && recoveryClass != RecoveryHandler.class) {
            targetTaskName = recoveryClass.getName();
        }

        RecoverySpec recoverySpec = RecoverySpec.of(targetTaskName, recoveryData);

        String specTaskName = resolveBeanName(method, target) + "#" + method.getName();

        RetryTaskSpec<Object> taskSpec = RetryTaskSpec.<Object>builder()
                .taskName(specTaskName)
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
            boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());
            if (isAsync) {
                return new CompletableFuture<>();
            }
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
}
