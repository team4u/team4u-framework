package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseAdminService;
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeaseUpdateRequest;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.retry.client.RetryCoordinator;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.store.DurableRetryStore;
import com.team4u.framework.retry.store.TaskHandle;
import com.team4u.framework.retry.store.record.*;
import com.team4u.framework.retry.store.serialize.HutoolRetryRecordSerializer;
import com.team4u.framework.retry.store.serialize.RetryRecordSerializer;
import lombok.Setter;

import java.time.Instant;
import java.util.Optional;

/**
 * 基于 team4u-lease 实现的持久化存储与协调器
 */
public class LeaseDurableRetryStore implements DurableRetryStore, RetryCoordinator {

    private static final long PREPARED_INTENT_DELAY_MILLIS = 3650L * 24L * 60L * 60L * 1000L;

    private final LeaseProducer producer;
    private final LeaseAdminService adminService;
    private final String queue;

    @Setter
    private RetryRecordSerializer serializer = HutoolRetryRecordSerializer.INSTANCE;

    public LeaseDurableRetryStore(LeaseBackend backend) {
        this(backend, backend, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public LeaseDurableRetryStore(LeaseProducer producer, LeaseAdminService adminService, String queue) {
        this.producer = producer;
        this.adminService = adminService;
        this.queue = (queue == null || queue.trim().isEmpty()) ? RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE : queue;
    }

    private void assertApplied(String operation, String taskId, LeaseAdminResult result) {
        if (result != LeaseAdminResult.APPLIED) {
            throw new IllegalStateException(
                    "Lease " + operation + " was not applied for taskId=" + taskId + ", result=" + result);
        }
    }

    @Override
    public TaskHandle create(RetryRecord initialRecord) {
        String taskId = producer.publish(LeasePublishRequest.builder()
                .queue(queue)
                .taskType(initialRecord.getRequest().getTaskName())
                .payload(serializer.serialize(initialRecord))
                .delayMillis(PREPARED_INTENT_DELAY_MILLIS) // Intentionally delayed to wait for schedule or foreground
                                                           // execution
                .build());
        return new TaskHandle(taskId);
    }

    @Override
    public void markRunning(String taskId, AttemptRecord attempt) {
        // Lease execution state can be updated directly via admin service if needed,
        // but typically running state is managed by Lease itself when a worker polls.
        // For foreground execution, we can force update the payload state.
    }

    @Override
    public void scheduleNext(String taskId, AttemptRecord attempt, Instant nextRunAt, FailureRecord failure) {
        // In Lease backend, scheduleNext is primarily used to record the next intended
        // state.
        // The actual scheduling is done via Coordinator's schedule().
    }

    @Override
    public void markSucceeded(String taskId, SuccessRecord success) {
        assertApplied("closeSucceeded", taskId, adminService.close(taskId, LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.SUCCEEDED)
                .build()));
    }

    @Override
    public void markFailed(String taskId, FailureRecord failure) {
        assertApplied("closeFailed", taskId, adminService.close(taskId, LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.FAILED)
                .failureReason(LeaseTaskFailureReason.RETRY_EXHAUSTED)
                .errorMessage(failure.getErrorMessage())
                .build()));
    }

    @Override
    public void cancel(String taskId, CancelRecord cancel) {
        assertApplied("cancel", taskId, adminService.close(taskId, LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.CANCELLED)
                .errorMessage(cancel.getReason())
                .build()));
    }

    @Override
    public Optional<RetryRecord> get(String taskId) {
        // LeaseAdminService doesn't have a direct get() for payload easily exposed in
        // the standard subset,
        // but if it does, we can implement it. For now return empty.
        return Optional.empty();
    }

    @Override
    public void schedule(RetryRecord record, long delayMillis) {
        // This is the Coordinator interface method to actually tell the backend to run
        // it.
        // Update payload first
        assertApplied("updatePayload", record.getTaskId(), adminService.update(LeaseUpdateRequest.builder()
                .taskId(record.getTaskId())
                .payload(serializer.serialize(record))
                .build()));

        assertApplied("schedule", record.getTaskId(), adminService.reschedule(record.getTaskId(), delayMillis));
    }
}
