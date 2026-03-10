package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryExecutionContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.store.record.RetryRecord;
import com.team4u.framework.retry.store.serialize.HutoolRetryRecordSerializer;
import com.team4u.framework.retry.store.serialize.RetryRecordSerializer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * 将 {@link RecoveryHandler} 适配为 {@link LeaseTaskHandler} 的包装类。
 */
@Slf4j
@Getter
public class RecoveryHandlerLeaseTaskHandlerAdapter implements LeaseTaskHandler {
    @SuppressWarnings("rawtypes")
    private final RecoveryHandler delegate;

    @Setter
    private RetryRecordSerializer serializer = HutoolRetryRecordSerializer.INSTANCE;

    public RecoveryHandlerLeaseTaskHandlerAdapter(RecoveryHandler<?> delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void handle(LeaseExecutionContext context) {
        RetryRecord record = serializer.deserialize(context.getPayload());
        markRunning(record);

        RecoveryContext recoveryContext = RecoveryContext.builder()
                .taskId(record.getTaskId())
                .attempt(record.getState().getAttempts() + 1)
                .build();

        try {
            RecoveryExecutionContext.run(
                    () -> delegate.recover(record.getRequest().getRecovery().getPayload(), recoveryContext));
            record.getState().setStatus(RetryStatus.SUCCEEDED);
            record.getState().setNextRunAt(null);
            String serializedRecord = serializer.serialize(record);

            LeaseRuntimeResult result = context.getRuntimeClient().close(
                    context.getHandle(),
                    LeaseCloseRequest.builder()
                            .outcome(LeaseTaskOutcome.SUCCEEDED)
                            .payload(serializedRecord)
                            .build());
            assertApplied(result, "closeSucceeded", record.getTaskId());
            context.markLifecycleHandled();

        } catch (Throwable cause) {
            handleFailure(context, record, cause);
        }
    }

    private void markRunning(RetryRecord record) {
        record.getState().setStatus(RetryStatus.RUNNING);
        record.getState().setNextRunAt(null);
    }

    private void handleFailure(LeaseExecutionContext context, RetryRecord record, Throwable cause) {
        RetryPolicy policy = record.getRequest().getPolicy();
        int attempts = record.getState().getAttempts() + 1;
        record.getState().setAttempts(attempts);
        record.getState().setLastErrorCode(cause.getClass().getSimpleName());
        record.getState().setLastErrorMessage(cause.getMessage());

        if (!policy.canRetry(attempts, cause)) {
            log.error("Task failed closed: {}", record.getTaskId(), cause);
            record.getState().setStatus(RetryStatus.FAILED);
            record.getState().setNextRunAt(null);
            String serializedRecord = serializer.serialize(record);
            LeaseRuntimeResult result = context.getRuntimeClient().close(
                    context.getHandle(),
                    LeaseCloseRequest.builder()
                            .outcome(LeaseTaskOutcome.FAILED)
                            .failureReason(LeaseTaskFailureReason.RETRY_EXHAUSTED)
                            .errorMessage(cause.getMessage())
                            .payload(serializedRecord)
                            .build());
            assertApplied(result, "closeFailed", record.getTaskId());
            context.markLifecycleHandled();
        } else {
            long delayMillis = policy.getDelayMillis(attempts);
            log.info("Task failed, retrying in {}ms: {}", delayMillis, record.getTaskId());

            record.getState().setStatus(RetryStatus.SCHEDULED);
            record.getState().setNextRunAt(Instant.now().plusMillis(delayMillis));
            String serializedRecord = serializer.serialize(record);

            LeaseRuntimeResult result = context.getRuntimeClient().release(
                    context.getHandle(),
                    LeaseReleaseRequest.builder()
                            .delayMillis(delayMillis)
                            .payload(serializedRecord)
                            .errorMessage(cause.getMessage())
                            .build());
            assertApplied(result, "release", record.getTaskId());
            context.markLifecycleHandled();
        }
    }

    private void assertApplied(LeaseRuntimeResult result, String operation, String taskId) {
        if (result != LeaseRuntimeResult.APPLIED) {
            throw new IllegalStateException(
                    "Failed to " + operation + " lease task: " + taskId + ", result: " + result);
        }
    }
}
