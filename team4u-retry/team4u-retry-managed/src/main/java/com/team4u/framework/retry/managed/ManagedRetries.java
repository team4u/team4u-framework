package com.team4u.framework.retry.managed;

import com.team4u.framework.retry.api.RecoverySpec;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.managed.submit.RetryTaskSpec;

import java.util.concurrent.Callable;

/**
 * MANAGED 重试门面。
 */
public final class ManagedRetries {

    private ManagedRetries() {
    }

    /**
     * 创建 MANAGED 执行入口。
     *
     * @param managedClient MANAGED 重试客户端
     * @return 绑定指定客户端的执行计划
     * @throws IllegalArgumentException 当 {@code managedClient} 为空时抛出
     */
    public static ManagedExecution with(ManagedRetryClient managedClient) {
        if (managedClient == null) {
            throw new IllegalArgumentException("ManagedRetryClient must not be null");
        }
        return new ManagedExecution(managedClient);
    }

    /**
     * MANAGED 执行计划。
     */
    public static final class ManagedExecution {
        private final ManagedRetryClient managedClient;
        private String taskType;
        private String idempotencyKey;
        private String payload;
        private RetryPolicy policy;

        private ManagedExecution(ManagedRetryClient managedClient) {
            this.managedClient = managedClient;
        }

        public ManagedExecution taskType(String taskType) {
            this.taskType = taskType;
            return this;
        }

        public ManagedExecution idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public ManagedExecution payload(String payload) {
            this.payload = payload;
            return this;
        }

        public ManagedExecution policy(RetryPolicy policy) {
            this.policy = policy;
            return this;
        }

        public <T> RetryTaskSpec<T> toSpec(Callable<T> task) {
            validateInputs(task);
            return RetryTaskSpec.<T>builder()
                    .idempotencyKey(idempotencyKey)
                    .executor(task)
                    .recovery(RecoverySpec.of(taskType, payload))
                    .policy(policy)
                    .build();
        }

        public <T> ManagedSubmitResult<T> call(Callable<T> task) {
            return managedClient.submit(toSpec(task));
        }

        private <T> void validateInputs(Callable<T> task) {
            if (isBlank(taskType)) {
                throw new IllegalStateException("Managed taskType must not be blank");
            }
            if (isBlank(idempotencyKey)) {
                throw new IllegalStateException("Managed idempotencyKey must not be blank");
            }
            if (policy == null) {
                throw new IllegalStateException("Managed RetryPolicy must be configured before calling task");
            }
            if (policy.getForegroundMaxRetries() == null) {
                throw new IllegalStateException(
                        "Managed RetryPolicy must configure foregroundMaxRetries before calling task");
            }
            if (task == null) {
                throw new IllegalArgumentException("Task must not be null");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}
