package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.client.RetryCoordinator;
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
    private final RetryCoordinator coordinator;

    @Setter
    private RetryRecordSerializer serializer = HutoolRetryRecordSerializer.INSTANCE;

    public RecoveryHandlerLeaseTaskHandlerAdapter(RecoveryHandler<?> delegate, RetryCoordinator coordinator) {
        this.delegate = delegate;
        this.coordinator = coordinator;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void handle(LeaseExecutionContext context) {
        RetryRecord record = serializer.deserialize(context.getPayload());

        RecoveryContext recoveryContext = RecoveryContext.builder()
                .taskId(record.getTaskId())
                .attempt(record.getState().getAttempts() + 1)
                .build();

        try {
            RecoveryExecutionContext.run(
                    () -> delegate.recover(record.getRequest().getRecovery().getPayload(), recoveryContext));

            // 成功，通过 coordinator 或者直接 close lease（在 worker 里本身就是 lease 上下文，可以直接 close）
            LeaseRuntimeResult result = context.getRuntimeClient().close(
                    context.getHandle(),
                    LeaseCloseRequest.succeeded());
            checkResult(result, "closeSucceeded", record.getTaskId());

        } catch (Throwable cause) {
            handleFailure(context, record, cause);
        }
    }

    private void handleFailure(LeaseExecutionContext context, RetryRecord record, Throwable cause) {
        RetryPolicy policy = record.getRequest().getPolicy();
        int attempts = record.getState().getAttempts() + 1;
        record.getState().setAttempts(attempts);
        record.getState().setLastErrorCode(cause.getClass().getSimpleName());
        record.getState().setLastErrorMessage(cause.getMessage());

        if (!policy.canRetry(attempts, cause)) {
            log.error("Task failed closed: {}", record.getTaskId(), cause);
            LeaseRuntimeResult result = context.getRuntimeClient().close(
                    context.getHandle(),
                    LeaseCloseRequest.failed(LeaseTaskFailureReason.RETRY_EXHAUSTED, cause.getMessage()));
            checkResult(result, "closeFailed", record.getTaskId());
        } else {
            long delayMillis = policy.getDelayMillis(attempts);
            log.info("Task failed, retrying in {}ms: {}", delayMillis, record.getTaskId());

            record.getState().setStatus(RetryStatus.SCHEDULED);
            record.getState().setNextRunAt(Instant.now().plusMillis(delayMillis));

            LeaseRuntimeResult result = context.getRuntimeClient().release(
                    context.getHandle(),
                    LeaseReleaseRequest.builder()
                            .delayMillis(delayMillis)
                            .payload(serializer.serialize(record))
                            .errorMessage(cause.getMessage())
                            .build());
            checkResult(result, "release", record.getTaskId());
        }
    }

    private void checkResult(LeaseRuntimeResult result, String operation, String taskId) {
        if (result != LeaseRuntimeResult.APPLIED) {
            log.error("Failed to {} lease task: {}, result: {}", operation, taskId, result);
        }
    }
}
