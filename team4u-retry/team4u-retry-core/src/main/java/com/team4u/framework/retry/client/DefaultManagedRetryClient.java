package com.team4u.framework.retry.client;

import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.domain.store.RetryState;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.store.DurableRetryStore;
import com.team4u.framework.retry.store.record.FailureRecord;
import com.team4u.framework.retry.store.record.RetryRecord;
import com.team4u.framework.retry.store.record.SuccessRecord;
import com.team4u.framework.retry.util.RetryExceptionUtil;
import lombok.Builder;

import java.time.Instant;
import java.util.Optional;

/**
 * 支持持久化托管的重试客户端默认实现。
 * <p>
 * 该客户端负责管理重试任务的完整生命周期，具备以下核心能力：
 * <ul>
 * <li>任务持久化：在重试开始前将任务意图存储到 {@link DurableRetryStore}，确保任务不会因进程崩溃而丢失。</li>
 * <li>前台尝试：在调用者线程中进行初步重试，以降低系统响应延迟。</li>
 * <li>后台调度移交：当前台尝试次数达到上限且任务仍需重试时，通过 {@link RetryCoordinator} 移交给后台调度器。</li>
 * </ul>
 */
public class DefaultManagedRetryClient implements ManagedRetryClient {

    /**
     * 持久化存储引擎，用于记录任务状态
     */
    private final DurableRetryStore store;

    /**
     * 重试协调中心，用于处理后台调度
     */
    private final RetryCoordinator coordinator;

    /**
     * 默认重试策略，在任务未显式指定策略时生效
     */
    private final RetryPolicy defaultPolicy;

    /**
     * 构建托管模式下的重试客户端。
     *
     * @param store         持久化存储引擎，用于任务状态的持久化，不可为空
     * @param coordinator   重试协调中心，负责将任务分发给后台调度器，不可为空
     * @param defaultPolicy 默认重试策略，当任务规格中未明确指定策略时作为兜底使用
     */
    @Builder
    public DefaultManagedRetryClient(
            DurableRetryStore store,
            RetryCoordinator coordinator,
            RetryPolicy defaultPolicy) {
        if (store == null) {
            throw new IllegalStateException("DurableRetryStore is required in MANAGED mode");
        }
        if (coordinator == null) {
            throw new IllegalStateException("RetryCoordinator is required in MANAGED mode");
        }

        this.store = store;
        this.coordinator = coordinator;
        this.defaultPolicy = defaultPolicy;
    }

    @Override
    public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
        // 解析待执行的重试策略并校验规格合法性
        RetryPolicy policy = resolvePolicy(spec);
        ManagedSubmitResult<T> validationResult = validateSpec(spec, policy);
        if (validationResult != null) {
            return validationResult;
        }

        // 初始化重试记录并持久化到存储中，确保重试意图已稳固存储
        RetryRecord record;
        try {
            record = createAndPersistRecord(spec, policy);
        } catch (Exception e) {
            return new ManagedSubmitResult.Rejected<>("Failed to persist initial retry intent: " + e.getMessage());
        }

        // 开始处理重试流程，优先在当前线程进行尝试
        return processRetry(spec, record, policy);
    }

    /**
     * 根据任务规格解析最终生效的重试策略。
     * <p>
     * 优先级：任务规格中指定的策略 > 客户端设置的默认策略。
     *
     * @param spec 任务规格
     * @return 解析后的重试策略
     */
    private RetryPolicy resolvePolicy(RetryTaskSpec<?> spec) {
        return Optional.ofNullable(spec.getPolicy()).orElse(defaultPolicy);
    }

    /**
     * 验证任务规格是否满足托管模式的必要条件。
     * <p>
     * 托管模式要求：显式配置前台重试次数、提供幂等键、运行器以及恢复规格（包含合法的任务类型）。
     *
     * @param spec   任务规格
     * @param policy 待验证的策略
     * @param <T>    任务返回值类型
     * @return 验证结果，若通过则返回 null，否则返回包含驳回原因的实例
     */
    private <T> ManagedSubmitResult<T> validateSpec(RetryTaskSpec<T> spec, RetryPolicy policy) {
        if (policy == null || policy.getForegroundMaxRetries() == null) {
            return new ManagedSubmitResult.Rejected<>(
                    "MANAGED mode requires a retry policy with foregroundMaxRetries explicitly configured");
        }

        if (isBlank(spec.getIdempotencyKey())) {
            return new ManagedSubmitResult.Rejected<>(
                    "MANAGED mode requires RetryTaskSpec.idempotencyKey");
        }
        if (spec.getExecutor() == null) {
            return new ManagedSubmitResult.Rejected<>(
                    "MANAGED mode requires RetryTaskSpec.executor");
        }
        if (spec.getRecovery() == null || isBlank(spec.getRecovery().getTaskType())) {
            return new ManagedSubmitResult.Rejected<>(
                    "MANAGED mode requires a RecoverySpec with a valid taskType");
        }
        return null;
    }

    /**
     * 构建并存储初始重试记录。
     * <p>
     * 在开始任何执行尝试前，先将任务的基本信息（任务请求和初始状态）持久化，并将分配的任务 ID 回填到对象中。
     *
     * @param spec   任务规格
     * @param policy 使用的重试策略
     * @return 已持久化的重试记录对象
     * @throws Exception 若持久化过程发生异常
     */
    private RetryRecord createAndPersistRecord(RetryTaskSpec<?> spec, RetryPolicy policy) throws Exception {
        RetryRequest request = RetryRequest.builder()
                .taskType(spec.getRecovery().getTaskType())
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
     * <p>
     * 目前默认先尝试在前台（当前线程）执行。
     */
    private <T> ManagedSubmitResult<T> processRetry(RetryTaskSpec<T> spec, RetryRecord record, RetryPolicy policy) {
        return executeInForeground(spec, record, policy);
    }

    /**
     * 在调用者线程（前台）执行重试。
     * <p>
     * 逻辑包括循环执行业务任务、处理成功状态、计算退避时间以及在达到前台尝试上限时移交给后台。
     */
    private <T> ManagedSubmitResult<T> executeInForeground(RetryTaskSpec<T> spec, RetryRecord record,
                                                           RetryPolicy policy) {
        int foregroundAttempts = policy.getForegroundMaxRetries() + 1;
        int attempts = 0;

        while (true) {
            attempts++;
            markForegroundRunning(record, attempts);

            try {
                T result = spec.getExecutor().call();

                handleSuccess(record);
                return new ManagedSubmitResult.Completed<>(result);

            } catch (Throwable ex) {
                Throwable cause = normalize(ex);
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                boolean canRetry = policy.canRetry(attempts, cause);
                FailureRecord failureRecord = createFailureRecord(cause);

                if (!canRetry) {
                    handleFinalFailure(record, attempts, failureRecord);
                    return new ManagedSubmitResult.Failed<>(cause);
                }

                if (attempts < foregroundAttempts) {
                    InterruptedException interrupted = handleForegroundBackoff(record, attempts, policy, failureRecord);
                    if (interrupted != null) {
                        FailureRecord interruptedFailure = createFailureRecord(interrupted);
                        handleFinalFailure(record, attempts, interruptedFailure);
                        return new ManagedSubmitResult.Failed<>(interrupted);
                    }
                } else {
                    return handleForegroundExhausted(record, attempts, policy, failureRecord);
                }
            }
        }
    }

    /**
     * 根据异常原因创建失败记录。
     */
    private FailureRecord createFailureRecord(Throwable cause) {
        return FailureRecord.builder()
                .errorCode(cause.getClass().getSimpleName())
                .errorMessage(cause.getMessage() != null ? cause.getMessage() : "")
                .failedAt(Instant.now())
                .build();
    }

    /**
     * 将失败相关的状态应用到重试记录中。
     */
    private void applyFailureState(
            RetryRecord record,
            int attempts,
            RetryStatus status,
            Instant nextRunAt,
            FailureRecord failureRecord) {
        RetryState state = record.getState();
        state.setAttempts(attempts);
        state.setStatus(status);
        state.setNextRunAt(nextRunAt);
        state.setLastErrorCode(failureRecord.getErrorCode());
        state.setLastErrorMessage(failureRecord.getErrorMessage());
    }

    /**
     * 标记当前任务正在前台运行。
     */
    private void markForegroundRunning(RetryRecord record, int attempts) {
        RetryState state = record.getState();
        state.setAttempts(attempts - 1);
        state.setStatus(RetryStatus.RUNNING);
        state.setNextRunAt(null);
    }

    /**
     * 处理任务执行成功的情况，更新存储中的状态。
     */
    private void handleSuccess(RetryRecord record) {
        RetryState state = record.getState();
        state.setStatus(RetryStatus.SUCCEEDED);
        state.setNextRunAt(null);
        store.markSucceeded(record.getTaskId(), SuccessRecord.builder().succeededAt(Instant.now()).build());
    }

    /**
     * 处理任务最终失败的情况，标记任务不再重试。
     */
    private void handleFinalFailure(RetryRecord record, int attempts, FailureRecord failureRecord) {
        applyFailureState(record, attempts, RetryStatus.FAILED, null, failureRecord);
        store.markFailed(record.getTaskId(), failureRecord);
    }

    /**
     * 处理前台重试流程中的间隔退避。
     * <p>
     * 计算并设置下一次执行时间，并将当前状态标记为“待调度”状态以通知相关方，随后在当前线程阻塞。
     *
     * @param record   当前任务记录
     * @param attempts 已执行的重试次数
     * @param policy   重试策略
     * @param failure  本次失败的原因详情
     * @return 若休眠过程被中断则返回中断异常，否则返回 null
     */
    private InterruptedException handleForegroundBackoff(
            RetryRecord record,
            int attempts,
            RetryPolicy policy,
            FailureRecord failure) {
        long delayMillis = policy.getDelayMillis(attempts);
        Instant nextRunAt = Instant.now().plusMillis(delayMillis);
        applyFailureState(record, attempts, RetryStatus.SCHEDULED, nextRunAt, failure);
        try {
            sleepQuietly(delayMillis);
            return null;
        } catch (InterruptedException ie) {
            InterruptedException interrupted = new InterruptedException("Foreground backoff sleep was interrupted");
            interrupted.initCause(ie);
            return interrupted;
        }
    }

    /**
     * 处理在前台尝试次数耗尽后的逻辑转移。
     * <p>
     * 将任务从前台移交给后台调度系统，以实现更长周期或更稳定的后续重试。
     *
     * @param record   当前任务记录
     * @param attempts 已完成的前台尝试次数
     * @param policy   重试策略
     * @param failure  最后一次导致移交的失败详情
     * @param <T>      任务返回值类型
     * @return 标记为已受理（调度中）的执行结果
     */
    private <T> ManagedSubmitResult<T> handleForegroundExhausted(
            RetryRecord record,
            int attempts,
            RetryPolicy policy,
            FailureRecord failure) {
        long delayMillis = policy.getDelayMillis(attempts);
        Instant nextRunAt = Instant.now().plusMillis(delayMillis);

        applyFailureState(record, attempts, RetryStatus.SCHEDULED, nextRunAt, failure);

        return scheduleToBackground(record, delayMillis);
    }

    /**
     * 调用后台协调中心进行任务调度。
     *
     * @param record      重试记录
     * @param delayMillis 距离下次运行的延迟毫秒数
     * @param <T>         任务返回值类型
     * @return 受理结果
     */
    private <T> ManagedSubmitResult<T> scheduleToBackground(RetryRecord record, long delayMillis) {
        coordinator.schedule(record, delayMillis);
        return new ManagedSubmitResult.Accepted<>(
                record.getTaskId(),
                RetryStatus.SCHEDULED.name(),
                record.getState().getNextRunAt());
    }

    /**
     * 让当前线程安静地进入休眠。
     * <p>
     * 若被中断，会重新设置中断状态并向外抛出 {@link InterruptedException}。
     *
     * @param delay 延迟毫秒数
     * @throws InterruptedException 若休眠被中断
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
     * 标准化异常对象，尝试提取业务或逻辑上的根本原因。
     * <p>
     * 若异常包装过深（如通过反射、Future等），该方法会将底层真实异常剥离以便后续判断。
     *
     * @param ex 捕获到的原始异常
     * @return 标准化后的根本原因异常
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

    /**
     * 检查字符串是否为空或仅包含空白字符。
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
