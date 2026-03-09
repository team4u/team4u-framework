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

public class DefaultManagedRetryClient implements ManagedRetryClient {

    private final DurableRetryStore store;
    private final RetryCoordinator coordinator;
    private final RetryPolicy defaultPolicy;

    @Builder
    public DefaultManagedRetryClient(
            DurableRetryStore store,
            RetryCoordinator coordinator,
            RetryPolicy defaultPolicy) {
        if (store == null) {
            throw new IllegalStateException("DurableRetryStore is required for MANAGED mode");
        }
        if (coordinator == null) {
            throw new IllegalStateException("RetryCoordinator is required for MANAGED mode");
        }

        this.store = store;
        this.coordinator = coordinator;
        this.defaultPolicy = defaultPolicy;
    }

    @Override
    public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
        RetryPolicy policy = Optional.ofNullable(spec.getPolicy()).orElse(defaultPolicy);
        if (policy == null || policy.getForegroundAttempts() == null) {
            return new ManagedSubmitResult.Rejected<>(
                    "MANAGED mode requires a policy with explicitly configured foregroundAttempts.");
        }

        // Validate Recovery spec existence
        if (spec.getRecovery() == null || spec.getRecovery().getTaskName() == null) {
            return new ManagedSubmitResult.Rejected<>(
                    "MANAGED mode requires an explicit RecoverySpec with a valid taskName.");
        }

        // 1. Create durable record
        RetryRequest request = RetryRequest.builder()
                .taskName(spec.getTaskName())
                .idempotencyKey(spec.getIdempotencyKey())
                .recovery(spec.getRecovery())
                .policy(policy)
                .createdAt(Instant.now())
                .build();

        RetryState initialState = RetryState.builder()
                .attempts(0)
                .status(RetryStatus.PREPARED)
                .nextRunAt(Instant.now())
                .build();

        RetryRecord initialRecord = RetryRecord.builder()
                .request(request)
                .state(initialState)
                .build();

        TaskHandle handle;
        try {
            handle = store.create(initialRecord);
            initialRecord.setTaskId(handle.getTaskId());
            request.setTaskId(handle.getTaskId());
        } catch (Exception e) {
            return new ManagedSubmitResult.Rejected<>("Failed to persist initial retry intent: " + e.getMessage());
        }

        int foregroundAttempts = policy.getForegroundAttempts();

        // 2. Are foreground attempts available?
        if (foregroundAttempts <= 0) {
            // Handoff to coordinator immediately without attempting foreground
            coordinator.schedule(initialRecord, 0);
            return new ManagedSubmitResult.Accepted<>(handle.getTaskId(), RetryStatus.SCHEDULED.name(), Instant.now());
        }

        // 3. Foreground attempt logic
        int attempts = 0;
        while (true) {
            attempts++;
            AttemptRecord attemptRecord = AttemptRecord.builder()
                    .attemptAt(Instant.now())
                    .workerId("foreground") // Can be replaced by actual node ID
                    .build();

            try {
                store.markRunning(handle.getTaskId(), attemptRecord);
                T result = spec.getExecutor().call();
                // Success
                store.markSucceeded(handle.getTaskId(), SuccessRecord.builder().succeededAt(Instant.now()).build());
                return new ManagedSubmitResult.Completed<>(result);

            } catch (Throwable ex) {
                Throwable cause = normalize(ex);
                boolean canRetry = policy.canRetry(attempts, cause);

                FailureRecord failureRecord = FailureRecord.builder()
                        .errorCode(cause.getClass().getSimpleName())
                        .errorMessage(cause.getMessage() != null ? cause.getMessage() : "")
                        .failedAt(Instant.now())
                        .build();

                initialState.setAttempts(attempts);
                initialState.setLastErrorCode(failureRecord.getErrorCode());
                initialState.setLastErrorMessage(failureRecord.getErrorMessage());

                if (!canRetry) {
                    // Final failure
                    store.markFailed(handle.getTaskId(), failureRecord);
                    return new ManagedSubmitResult.Failed<>(cause);
                }

                // Wait, do we continue foreground retrying if we haven't exhausted
                // foregroundAttempts?
                if (attempts < foregroundAttempts) {
                    long delayMillis = policy.getDelayMillis(attempts);
                    // Update state to schedule next attempt in foreground
                    store.scheduleNext(handle.getTaskId(), attemptRecord, Instant.now().plusMillis(delayMillis),
                            failureRecord);
                    try {
                        sleepQuietly(delayMillis);
                    } catch (InterruptedException ie) {
                        return new ManagedSubmitResult.Failed<>(ie);
                    }
                } else {
                    // Exhausted foreground budget but overall policy says we can still retry.
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
