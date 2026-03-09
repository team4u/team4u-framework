package com.team4u.framework.retry.client;

import com.team4u.framework.retry.RetryExceptionUtil;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.domain.store.RetryState;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.store.DurableRetryStore;
import com.team4u.framework.retry.store.TaskHandle;
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
        // 确定最终使用的重试策略
        RetryPolicy policy = Optional.ofNullable(spec.getPolicy()).orElse(defaultPolicy);
        if (policy == null || policy.getForegroundAttempts() == null) {
            return new ManagedSubmitResult.Rejected<>(
                    "托管模式要求必须显式配置包含 foregroundAttempts 属性的重试策略");
        }

        // 校验恢复规范，确保任务失败后有明确的处理指向
        if (spec.getRecovery() == null || spec.getRecovery().getTaskName() == null) {
            return new ManagedSubmitResult.Rejected<>(
                    "托管模式要求必须提供包含有效任务名称的 RecoverySpec 对象");
        }

        // 创建持久化请求记录
        RetryRequest request = RetryRequest.builder()
                .taskName(spec.getTaskName())
                .idempotencyKey(spec.getIdempotencyKey())
                .recovery(spec.getRecovery())
                .policy(policy)
                .createdAt(Instant.now())
                .build();

        // 初始化重试状态
        RetryState initialState = RetryState.builder()
                .attempts(0)
                .status(RetryStatus.PREPARED)
                .nextRunAt(Instant.now())
                .build();

        // 组合成完整的重试记录
        RetryRecord initialRecord = RetryRecord.builder()
                .request(request)
                .state(initialState)
                .build();

        TaskHandle handle;
        try {
            // 将重试意图持久化到存储中
            handle = store.create(initialRecord);
            initialRecord.setTaskId(handle.getTaskId());
            request.setTaskId(handle.getTaskId());
        } catch (Exception e) {
            return new ManagedSubmitResult.Rejected<>("持久化初始重试意图失败: " + e.getMessage());
        }

        int foregroundAttempts = policy.getForegroundAttempts();

        // 判断是否需要立即进行后台调度
        if (foregroundAttempts <= 0) {
            // 如果前台重试次数为 0，则直接移交给协调中心进行后台处理
            coordinator.schedule(initialRecord, 0);
            return new ManagedSubmitResult.Accepted<>(handle.getTaskId(), RetryStatus.SCHEDULED.name(), Instant.now());
        }

        // 开始前台重试逻辑
        int attempts = 0;
        while (true) {
            attempts++;
            AttemptRecord attemptRecord = AttemptRecord.builder()
                    .attemptAt(Instant.now())
                    .workerId("foreground") // 标记为前台执行节点
                    .build();

            try {
                // 更新存储状态为执行中
                store.markRunning(handle.getTaskId(), attemptRecord);
                // 调用实际业务逻辑
                T result = spec.getExecutor().call();

                // 执行成功，标记任务完成并返回结果
                store.markSucceeded(handle.getTaskId(), SuccessRecord.builder().succeededAt(Instant.now()).build());
                return new ManagedSubmitResult.Completed<>(result);

            } catch (Throwable ex) {
                // 异常处理与重试决策
                Throwable cause = normalize(ex);
                boolean canRetry = policy.canRetry(attempts, cause);

                FailureRecord failureRecord = FailureRecord.builder()
                        .errorCode(cause.getClass().getSimpleName())
                        .errorMessage(cause.getMessage() != null ? cause.getMessage() : "")
                        .failedAt(Instant.now())
                        .build();

                // 更新内存中的状态对象
                initialState.setAttempts(attempts);
                initialState.setLastErrorCode(failureRecord.getErrorCode());
                initialState.setLastErrorMessage(failureRecord.getErrorMessage());

                if (!canRetry) {
                    // 如果达到策略限制或遇到不可重试异常，标记最终失败
                    store.markFailed(handle.getTaskId(), failureRecord);
                    return new ManagedSubmitResult.Failed<>(cause);
                }

                // 判断是否仍处于前台重试预算内
                if (attempts < foregroundAttempts) {
                    long delayMillis = policy.getDelayMillis(attempts);
                    // 在存储中更新下一次尝试时间
                    store.scheduleNext(handle.getTaskId(), attemptRecord, Instant.now().plusMillis(delayMillis),
                            failureRecord);
                    try {
                        // 在当前线程进行退避休眠
                        sleepQuietly(delayMillis);
                    } catch (InterruptedException ie) {
                        return new ManagedSubmitResult.Failed<>(ie);
                    }
                } else {
                    // 前台预算已耗尽，但策略允许继续重试，此时移交给后台调度系统
                    long delayMillis = policy.getDelayMillis(attempts);
                    Instant nextRunAt = Instant.now().plusMillis(delayMillis);
                    store.scheduleNext(handle.getTaskId(), attemptRecord, nextRunAt, failureRecord);
                    initialState.setStatus(RetryStatus.SCHEDULED);
                    initialState.setNextRunAt(nextRunAt);

                    coordinator.schedule(initialRecord, delayMillis);
                    return new ManagedSubmitResult.Accepted<>(handle.getTaskId(), RetryStatus.SCHEDULED.name(),
                            nextRunAt);
                }
            }
        }
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
