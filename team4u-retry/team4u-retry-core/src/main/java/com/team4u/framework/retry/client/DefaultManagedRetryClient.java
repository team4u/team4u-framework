package com.team4u.framework.retry.client;

import com.team4u.framework.retry.RetryExceptionUtil;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.domain.store.RetryState;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.store.DurableRetryStore;
import com.team4u.framework.retry.store.record.AttemptRecord;
import com.team4u.framework.retry.store.record.FailureRecord;
import com.team4u.framework.retry.store.record.RetryRecord;
import com.team4u.framework.retry.store.record.SuccessRecord;
import lombok.Builder;

import java.time.Instant;
import java.util.Optional;

/**
 * 支持持久化托管的重试客户端默认实现。
 * <p>
 * 该客户端实现了重试任务的生命周期管理，包括任务持久化、前台重试尝试以及后台调度移交。
 * 它依赖 {@link DurableRetryStore} 进行状态存储，并通过 {@link RetryCoordinator} 进行后台任务分发。
 */
public class DefaultManagedRetryClient implements ManagedRetryClient {

    private final DurableRetryStore store;
    private final RetryCoordinator coordinator;
    private final RetryPolicy defaultPolicy;

    /**
     * 构建托管重试客户端。
     *
     * @param store         持久化存储引擎，不可为空
     * @param coordinator   重试协调中心，用于后台调度，不可为空
     * @param defaultPolicy 默认重试策略，当任务未指定策略时使用
     */
    @Builder
    public DefaultManagedRetryClient(
            DurableRetryStore store,
            RetryCoordinator coordinator,
            RetryPolicy defaultPolicy) {
        if (store == null) {
            throw new IllegalStateException("托管模式下 DurableRetryStore 是必须的");
        }
        if (coordinator == null) {
            throw new IllegalStateException("托管模式下 RetryCoordinator 是必须的");
        }

        this.store = store;
        this.coordinator = coordinator;
        this.defaultPolicy = defaultPolicy;
    }

    @Override
    public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
        // 1. 策略与规格校验
        RetryPolicy policy = resolvePolicy(spec);
        ManagedSubmitResult<T> validationResult = validateSpec(spec, policy);
        if (validationResult != null) {
            return validationResult;
        }

        // 2. 初始化并持久化重试记录
        RetryRecord record;
        try {
            record = createAndPersistRecord(spec, policy);
        } catch (Exception e) {
            return new ManagedSubmitResult.Rejected<>("持久化初始重试意图失败: " + e.getMessage());
        }

        // 3. 执行重试逻辑（前台尝试或直接后台调度）
        return processRetry(spec, record, policy);
    }

    /**
     * 解析最终使用的重试策略。
     */
    private RetryPolicy resolvePolicy(RetryTaskSpec<?> spec) {
        return Optional.ofNullable(spec.getPolicy()).orElse(defaultPolicy);
    }

    /**
     * 校验任务规格是否符合托管模式要求。
     */
    private <T> ManagedSubmitResult<T> validateSpec(RetryTaskSpec<T> spec, RetryPolicy policy) {
        if (policy == null || policy.getForegroundAttempts() == null) {
            return new ManagedSubmitResult.Rejected<>(
                    "托管模式要求必须显式配置包含 foregroundAttempts 属性的重试策略");
        }

        if (spec.getRecovery() == null || spec.getRecovery().getTaskName() == null) {
            return new ManagedSubmitResult.Rejected<>(
                    "托管模式要求必须提供包含有效任务名称的 RecoverySpec 对象");
        }
        return null;
    }

    /**
     * 创建并持久化初始重试记录。
     */
    private RetryRecord createAndPersistRecord(RetryTaskSpec<?> spec, RetryPolicy policy) throws Exception {
        RetryRequest request = RetryRequest.builder()
                .taskName(spec.getTaskName())
                .idempotencyKey(spec.getIdempotencyKey())
                .recovery(spec.getRecovery())
                .policy(policy)
                .createdAt(Instant.now())
                .build();

        RetryState state = RetryState.builder()
                .attempts(0)
                .status(RetryStatus.PREPARED)
                .nextRunAt(Instant.now())
                .build();

        RetryRecord record = RetryRecord.builder()
                .request(request)
                .state(state)
                .build();

        String taskId = store.create(record);
        record.setTaskId(taskId);
        request.setTaskId(taskId);
        return record;
    }

    /**
     * 处理具体的重试执行流程。
     */
    private <T> ManagedSubmitResult<T> processRetry(RetryTaskSpec<T> spec, RetryRecord record, RetryPolicy policy) {
        int foregroundAttempts = policy.getForegroundAttempts();

        // 如果没有前台重试预算，直接进入后台调度
        if (foregroundAttempts <= 0) {
            return scheduleToBackground(record, 0);
        }

        // 进入前台执行循环
        return executeInForeground(spec, record, policy);
    }

    /**
     * 在当前线程（前台）执行重试循环。
     */
    private <T> ManagedSubmitResult<T> executeInForeground(RetryTaskSpec<T> spec, RetryRecord record, RetryPolicy policy) {
        int foregroundAttempts = policy.getForegroundAttempts();
        int attempts = 0;

        while (true) {
            attempts++;
            AttemptRecord attemptRecord = createAttemptRecord();

            try {
                store.markRunning(record.getTaskId(), attemptRecord);

                T result = spec.getExecutor().call();

                handleSuccess(record.getTaskId());
                return new ManagedSubmitResult.Completed<>(result);

            } catch (Throwable ex) {
                Throwable cause = normalize(ex);
                boolean canRetry = policy.canRetry(attempts, cause);
                FailureRecord failureRecord = createFailureRecord(cause);

                // 更新记录中的状态信息
                updateRecordState(record, attempts, failureRecord);

                if (!canRetry) {
                    handleFinalFailure(record.getTaskId(), failureRecord);
                    return new ManagedSubmitResult.Failed<>(cause);
                }

                if (attempts < foregroundAttempts) {
                    // 继续前台重试
                    if (!handleForegroundBackoff(record.getTaskId(), attemptRecord, attempts, policy, failureRecord)) {
                        return new ManagedSubmitResult.Failed<>(new InterruptedException("前台退避休眠被中断"));
                    }
                } else {
                    // 前台预算耗尽，移交后台
                    return handleForegroundExhausted(record, attemptRecord, attempts, policy, failureRecord);
                }
            }
        }
    }

    private AttemptRecord createAttemptRecord() {
        return AttemptRecord.builder()
                .attemptAt(Instant.now())
                .workerId("foreground")
                .build();
    }

    private FailureRecord createFailureRecord(Throwable cause) {
        return FailureRecord.builder()
                .errorCode(cause.getClass().getSimpleName())
                .errorMessage(cause.getMessage() != null ? cause.getMessage() : "")
                .failedAt(Instant.now())
                .build();
    }

    private void updateRecordState(RetryRecord record, int attempts, FailureRecord failureRecord) {
        RetryState state = record.getState();
        state.setAttempts(attempts);
        state.setLastErrorCode(failureRecord.getErrorCode());
        state.setLastErrorMessage(failureRecord.getErrorMessage());
    }

    private void handleSuccess(String taskId) {
        store.markSucceeded(taskId, SuccessRecord.builder().succeededAt(Instant.now()).build());
    }

    private void handleFinalFailure(String taskId, FailureRecord failureRecord) {
        store.markFailed(taskId, failureRecord);
    }

    /**
     * 处理前台重试时的退避逻辑。
     *
     * @return true 表示休眠成功，false 表示休眠被中断
     */
    private boolean handleForegroundBackoff(String taskId, AttemptRecord attempt, int attempts, RetryPolicy policy, FailureRecord failure) {
        long delayMillis = policy.getDelayMillis(attempts);
        store.scheduleNext(taskId, attempt, Instant.now().plusMillis(delayMillis), failure);
        try {
            sleepQuietly(delayMillis);
            return true;
        } catch (InterruptedException ie) {
            return false;
        }
    }

    /**
     * 处理前台预算耗尽后的逻辑，进行后台调度移交。
     */
    private <T> ManagedSubmitResult<T> handleForegroundExhausted(RetryRecord record, AttemptRecord attempt, int attempts, RetryPolicy policy, FailureRecord failure) {
        long delayMillis = policy.getDelayMillis(attempts);
        Instant nextRunAt = Instant.now().plusMillis(delayMillis);

        store.scheduleNext(record.getTaskId(), attempt, nextRunAt, failure);

        record.getState().setStatus(RetryStatus.SCHEDULED);
        record.getState().setNextRunAt(nextRunAt);

        return scheduleToBackground(record, delayMillis);
    }

    /**
     * 封装后台调度逻辑。
     */
    private <T> ManagedSubmitResult<T> scheduleToBackground(RetryRecord record, long delayMillis) {
        coordinator.schedule(record, delayMillis);
        return new ManagedSubmitResult.Accepted<>(
                record.getTaskId(),
                RetryStatus.SCHEDULED.name(),
                record.getState().getNextRunAt()
        );
    }

    /**
     * 安静地让当前线程休眠指定的时间。
     */
    private void sleepQuietly(long delay) throws InterruptedException {
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    /**
     * 规范化异常，提取底层真实原因。
     */
    private Throwable normalize(Throwable ex) {
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return ex;
        }
        if (ex instanceof Error) {
            return ex;
        }
        Throwable cause = RetryExceptionUtil.unwrap(ex);
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return cause;
    }
}
