package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.TaskContext;
import com.team4u.framework.lease.api.TaskHandler;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryExecutionContext;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

/**
 * Adapts a string recovery handler to the task queue handler contract.
 */
@Getter
public class RecoveryHandlerTaskHandlerAdapter implements TaskHandler {

    private final StringRecoveryHandler delegate;
    private final RetryRecordSerializer serializer;

    public RecoveryHandlerTaskHandlerAdapter(
            StringRecoveryHandler delegate,
            RetryRecordSerializer serializer) {
        if (delegate == null) {
            throw new IllegalArgumentException("Task handler adapter requires StringRecoveryHandler");
        }
        if (serializer == null) {
            throw new IllegalArgumentException("RetryRecordSerializer must not be null");
        }
        this.delegate = delegate;
        this.serializer = serializer;
    }

    @Override
    public TaskResult handle(TaskContext context) {
        RetryRecord record;
        try {
            record = serializer.deserialize(context.getPayload());
        } catch (RuntimeException ex) {
            throw new RetryInfrastructureException(
                    "Failed to deserialize retry task payload: " + context.getTaskId(), ex);
        }

        record.setTaskId(context.getTaskId());
        if (record.getRequest() != null) {
            record.getRequest().setTaskId(context.getTaskId());
        }
        // Lease delivery count is queue-local; retry.state.attempts is the durable total.
        int attempts = record.getState().getAttempts() + 1;
        RecoveryContext recoveryContext = RecoveryContext.builder()
                .taskId(context.getTaskId())
                .attempt(attempts)
                .build();

        try {
            RecoveryExecutionContext.run(() ->
                    delegate.recover(record.getRequest().getRecovery().getPayload(), recoveryContext));
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new RetryInfrastructureException(
                    "Business recovery was interrupted; lease will expire and recover: "
                            + context.getTaskId(), cause);
        } catch (Exception cause) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RetryInfrastructureException(
                        "Business recovery finished with thread interrupt pending; "
                                + "lease will expire and recover: " + context.getTaskId(),
                        new InterruptedException("interrupt flag was already set"));
            }
            return failureResult(record, attempts, cause, context.getTaskId());
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new RetryInfrastructureException(
                    "Business recovery succeeded with thread interrupt pending; "
                            + "lease will expire and recover: " + context.getTaskId(),
                    new InterruptedException("interrupt flag was already set"));
        }
        record.getState().setAttempts(attempts);
        record.getState().setStatus(RetryStatus.SUCCEEDED);
        record.getState().setNextRunAt(null);
        record.getState().setSucceededAt(Instant.now());
        try {
            return TaskResult.success(serializer.serialize(record),
                    Collections.<String, String>emptyMap());
        } catch (RuntimeException ex) {
            throw new RetryInfrastructureException(
                    "Business recovery succeeded but its retry result could not be serialized: "
                            + context.getTaskId(), ex);
        }
    }

    private TaskResult failureResult(
            RetryRecord record, int attempts, Throwable cause, String taskId) {
        RetryPolicy policy = record.getRequest().getPolicy();
        record.getState().setLastErrorCode(cause.getClass().getSimpleName());
        record.getState().setLastErrorMessage(cause.getMessage());

        record.getState().setAttempts(attempts);
        boolean retryable = policy.canRetry(attempts, cause);
        if (!retryable) {
            record.getState().setStatus(RetryStatus.FAILED);
            record.getState().setNextRunAt(null);
            record.getState().setFailedAt(Instant.now());
        } else {
            record.getState().setStatus(RetryStatus.WAITING_RETRY);
            record.getState().setNextRunAt(Instant.now().plusMillis(
                    policy.getDelayMillis(attempts)));
        }
        String payload;
        try {
            payload = serializer.serialize(record);
        } catch (RuntimeException ex) {
            throw new RetryInfrastructureException(
                    "Business recovery failure result could not be serialized: "
                            + taskId, ex);
        }
        if (!retryable) {
            return TaskResult.failure(cause.getMessage(), payload,
                    Collections.<String, String>emptyMap());
        }
        return TaskResult.retryAfter(
                Duration.ofMillis(policy.getDelayMillis(attempts)), cause.getMessage(),
                payload, Collections.<String, String>emptyMap());
    }
}
