package com.team4u.framework.retry.managed.client;

import com.team4u.framework.retry.api.ManagedSubmitResult;
import com.team4u.framework.retry.managed.submit.RetryTaskSpec;
import com.team4u.framework.retry.managed.model.RetryRequest;
import com.team4u.framework.retry.managed.model.RetryState;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.managed.dispatch.DispatchResult;
import com.team4u.framework.retry.managed.dispatch.RetryDispatchCommand;
import com.team4u.framework.retry.managed.dispatch.RetryDispatcher;
import com.team4u.framework.retry.managed.store.RetryStore;
import com.team4u.framework.retry.managed.store.record.*;
import com.team4u.framework.retry.common.util.RetryExceptionUtil;
import lombok.Builder;

import java.time.Instant;
import java.util.Optional;

/**
 * 支持故障转移（Durable Handoff）的托管重试客户端默认实现。
 * <p>
 * 该客户端负责管理重试任务的完整生命周期，具备以下核心能力：
 * <ul>
 * <li>任务持久化：在重试开始前将任务意图存储到 {@link RetryStore}，确保任务即使在进程崩溃后也能恢复。</li>
 * <li>本地快速重试：在调用者线程（前台）进行初步重试，以降低系统响应延迟。</li>
 * <li>后台调度移交：当前台重试次数达到上限且任务仍需继续时，通过 {@link RetryDispatcher} 将任务移交给后台调度器。</li>
 * </ul>
 */
public class DefaultManagedRetryClient implements ManagedRetryClient {

    /**
     * 重试存储引擎，用于持久化任务及其状态
     */
    private final RetryStore store;

    /**
     * 重试分发器，用于将任务移交给后台调度
     */
    private final RetryDispatcher dispatcher;

    /**
     * 默认重试策略，在任务未显式指定策略时作为兜底使用
     */
    private final RetryPolicy defaultPolicy;

    /**
     * 构建托管模式下的重试客户端。
     *
     * @param store         重试存储引擎，不可为空
     * @param dispatcher    重试分发器，不可为空
     * @param defaultPolicy 默认重试策略，可选
     */
    @Builder
    public DefaultManagedRetryClient(
            RetryStore store,
            RetryDispatcher dispatcher,
            RetryPolicy defaultPolicy) {
        if (store == null) {
            throw new IllegalStateException("RetryStore is required in MANAGED mode");
        }
        if (dispatcher == null) {
            throw new IllegalStateException("RetryDispatcher is required in MANAGED mode");
        }
        this.store = store;
        this.dispatcher = dispatcher;
        this.defaultPolicy = defaultPolicy;
    }

    @Override
    public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
        if (spec == null) {
            throw new IllegalArgumentException("RetryTaskSpec must not be null");
        }
        // 解析待执行的重试策略
        RetryPolicy policy = resolvePolicy(spec);
        // 校验任务规格是否满足托管模式的要求
        validateSpec(spec, policy);

        SubmitRecord submitRecord;
        try {
            // 尝试创建重试记录，利用幂等键确保任务唯一性
            submitRecord = store.createIfAbsent(createRequest(spec, policy));
        } catch (Exception e) {
            return new ManagedSubmitResult.Rejected<>("Failed to persist initial retry intent: " + e.getMessage());
        }

        RetryRecord record = submitRecord.getRecord();
        // 如果记录已存在（非本次创建），则返回当前持久化快照，避免把终态误报为 Accepted
        if (!submitRecord.isCreated()) {
            return new ManagedSubmitResult.Existing<T>(
                    record.getTaskId(),
                    record.getState().getStatus(),
                    record.getState().getNextRunAt());
        }
        // 对于新创建的任务，优先在前台执行
        return executeInForeground(spec, record, policy);
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
     * 托管模式要求：必须配置前台重试上限、幂等键、运行器以及包含有效任务类型的恢复规格。
     *
     * @param spec   任务规格
     * @param policy 待验证的策略
     */
    private void validateSpec(RetryTaskSpec<?> spec, RetryPolicy policy) {
        if (policy == null || policy.getForegroundMaxRetries() == null) {
            throw new IllegalStateException(
                    "MANAGED mode requires a retry policy with foregroundMaxRetries explicitly configured");
        }
        if (isBlank(spec.getIdempotencyKey())) {
            throw new IllegalStateException("MANAGED mode requires RetryTaskSpec.idempotencyKey");
        }
        if (spec.getExecutor() == null) {
            throw new IllegalStateException("MANAGED mode requires RetryTaskSpec.executor");
        }
        if (spec.getRecovery() == null || isBlank(spec.getRecovery().getTaskType())) {
            throw new IllegalStateException(
                    "MANAGED mode requires a RecoverySpec with a valid taskType");
        }
    }

    /**
     * 创建初始的重试请求对象。
     *
     * @param spec   任务规格
     * @param policy 使用的重试策略
     * @return 包含任务请求和初始状态的创建请求
     */
    private RetryCreateRequest createRequest(RetryTaskSpec<?> spec, RetryPolicy policy) {
        Instant now = Instant.now();
        RetryRequest request = RetryRequest.builder()
                .taskType(spec.getRecovery().getTaskType())
                .idempotencyKey(spec.getIdempotencyKey())
                .recovery(spec.getRecovery())
                .policy(policy)
                .createdAt(now)
                .build();
        RetryState initialState = RetryState.builder()
                .attempts(0)
                .status(RetryStatus.ACCEPTED)
                .nextRunAt(now)
                .build();
        return RetryCreateRequest.builder()
                .request(request)
                .initialState(initialState)
                .build();
    }

    /**
     * 在调用者线程（前台）执行具体的任务重试。
     *
     * @param spec   任务规格
     * @param record 当前重试记录
     * @param policy 重试策略
     * @param <T>    任务返回值类型
     * @return 执行或分发后的最终结果
     */
    private <T> ManagedSubmitResult<T> executeInForeground(
            RetryTaskSpec<T> spec,
            RetryRecord record,
            RetryPolicy policy) {
        int maxForegroundExecutions = policy.getForegroundMaxRetries() + 1;
        int failedAttemptsSoFar = 0;

        while (true) {
            T result;
            try {
                // 执行业务逻辑
                result = spec.getExecutor().call();
            } catch (Throwable ex) {
                failedAttemptsSoFar++;
                Throwable cause = normalize(ex);
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                FailureRecord failure = createFailureRecord(cause);
                boolean canRetry = policy.canRetry(failedAttemptsSoFar, cause);
                // 若策略决定不再重试，则标记为最终失败
                if (!canRetry) {
                    markFinalFailure(record, failedAttemptsSoFar, failure);
                    return new ManagedSubmitResult.Failed<T>(cause);
                }
                // 若未达到前台重试上限，则在当前线程休眠退避后继续尝试
                if (failedAttemptsSoFar < maxForegroundExecutions) {
                    InterruptedException interrupted = sleepBeforeNextAttempt(policy, failedAttemptsSoFar);
                    if (interrupted != null) {
                        markFinalFailure(record, failedAttemptsSoFar, createFailureRecord(interrupted));
                        Thread.currentThread().interrupt();
                        return new ManagedSubmitResult.Failed<T>(interrupted);
                    }
                    continue;
                }
                // 达到前台上限，移交给后台处理
                return dispatchToBackground(record, failedAttemptsSoFar, failure, policy);
            }
            // Completed 只在 durable SUCCEEDED 写入成功后才成立。
            try {
                store.markSucceeded(record.getTaskId(), SuccessRecord.builder().succeededAt(Instant.now()).build());
            } catch (RuntimeException ex) {
                throw new DurableSuccessWriteException(record.getTaskId(), ex);
            }
            record.getState().setStatus(RetryStatus.SUCCEEDED);
            record.getState().setNextRunAt(null);
            record.getState().setAttempts(failedAttemptsSoFar + 1);
            return new ManagedSubmitResult.Completed<T>(result);
        }
    }

    /**
     * 将重试任务分发给后台调度系统。
     *
     * @param record              当前任务记录
     * @param failedAttemptsSoFar 截至当前已失败的次数
     * @param failure             最后一次失败的详细信息
     * @param policy              重试策略
     * @param <T>                 任务返回值类型
     * @return 标记为已接受（等待后台重试）的结果
     */
    private <T> ManagedSubmitResult<T> dispatchToBackground(
            RetryRecord record,
            int failedAttemptsSoFar,
            FailureRecord failure,
            RetryPolicy policy) {
        long delayMillis = policy.getDelayMillis(failedAttemptsSoFar);
        Instant nextRunAt = Instant.now().plusMillis(delayMillis);
        RetryTransition transition = RetryTransition.builder()
                .attempts(failedAttemptsSoFar)
                .nextRunAt(nextRunAt)
                .lastErrorCode(failure.getErrorCode())
                .lastErrorMessage(failure.getErrorMessage())
                .build();

        // 通过分发器移交任务
        DispatchResult dispatchResult;
        try {
            dispatchResult = dispatcher.dispatch(RetryDispatchCommand.builder()
                    .record(record)
                    .transition(transition)
                    .delayMillis(delayMillis)
                    .build());
        } catch (RuntimeException ex) {
            return new ManagedSubmitResult.Rejected<T>(
                    "Failed to hand off retry task to background dispatcher: " + ex.getMessage());
        }

        // 同步更新内存中的记录状态
        record.getState().setStatus(RetryStatus.WAITING_RETRY);
        record.getState().setAttempts(failedAttemptsSoFar);
        record.getState().setNextRunAt(dispatchResult.getNextRunAt());
        record.getState().setLastErrorCode(failure.getErrorCode());
        record.getState().setLastErrorMessage(failure.getErrorMessage());
        record.getState().setBackendTaskId(dispatchResult.getBackendTaskId());

        return new ManagedSubmitResult.Accepted<T>(
                record.getTaskId(),
                RetryStatus.WAITING_RETRY,
                dispatchResult.getNextRunAt());
    }

    /**
     * 标记任务为最终失败。
     */
    private void markFinalFailure(RetryRecord record, int attempts, FailureRecord failure) {
        store.markFailed(record.getTaskId(), failure);
        record.getState().setAttempts(attempts);
        record.getState().setStatus(RetryStatus.FAILED);
        record.getState().setLastErrorCode(failure.getErrorCode());
        record.getState().setLastErrorMessage(failure.getErrorMessage());
        record.getState().setNextRunAt(null);
    }

    /**
     * 在下一次重试尝试前进行休眠。
     *
     * @param policy   重试策略
     * @param attempts 当前尝试次数
     * @return 若休眠被中断返回异常，否则返回 null
     */
    private InterruptedException sleepBeforeNextAttempt(RetryPolicy policy, int attempts) {
        long delayMillis = policy.getDelayMillis(attempts);
        if (delayMillis <= 0) {
            return null;
        }
        try {
            Thread.sleep(delayMillis);
            return null;
        } catch (InterruptedException interrupted) {
            return interrupted;
        }
    }

    /**
     * 根据异常对象创建失败记录详情。
     */
    private FailureRecord createFailureRecord(Throwable throwable) {
        return FailureRecord.builder()
                .errorCode(throwable.getClass().getSimpleName())
                .errorMessage(throwable.getMessage())
                .failedAt(Instant.now())
                .build();
    }

    /**
     * 标准化异常对象，尝试剥离包装层。
     */
    private Throwable normalize(Throwable throwable) {
        return RetryExceptionUtil.unwrapAndRestoreInterrupt(throwable);
    }

    /**
     * 检查字符串是否为空或空白。
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
