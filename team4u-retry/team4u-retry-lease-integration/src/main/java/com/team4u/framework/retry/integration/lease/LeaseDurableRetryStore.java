package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseAdminService;
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeaseUpdateRequest;
import com.team4u.framework.retry.client.RetryCoordinator;
import com.team4u.framework.retry.store.DurableRetryStore;
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
    public String create(RetryRecord initialRecord) {
        return producer.publish(LeasePublishRequest.builder()
                .queue(queue)
                .taskType(initialRecord.getRequest().getTaskName())
                .payload(serializer.serialize(initialRecord))
                .delayMillis(PREPARED_INTENT_DELAY_MILLIS) // 故意延迟，以等待调度或前台执行
                .build());
    }

    @Override
    public void markRunning(String taskId, AttemptRecord attempt) {
        // 如果需要，可以通过管理服务直接更新 Lease 执行状态，
        // 但通常运行状态由 Lease 在工作节点轮询时自行管理。
        // 对于前台执行，我们可以强制更新负载（payload）状态。
    }

    @Override
    public void scheduleNext(String taskId, AttemptRecord attempt, Instant nextRunAt, FailureRecord failure) {
        // 在 Lease 后端中，scheduleNext 主要用于记录下一个预期的状态。
        // 实际的调度是通过协调器的 schedule() 方法完成的。
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
        // LeaseAdminService 在标准子集中没有直接暴露获取负载（payload）的 get() 方法，
        // 但如果后续支持，我们可以实现它。目前返回空。
        return Optional.empty();
    }

    @Override
    public void schedule(RetryRecord record, long delayMillis) {
        // 这是协调器接口方法，用于实际告知后端运行任务。
        // 首先更新负载（payload）
        assertApplied("updatePayload", record.getTaskId(), adminService.update(LeaseUpdateRequest.builder()
                .taskId(record.getTaskId())
                .payload(serializer.serialize(record))
                .build()));

        assertApplied("schedule", record.getTaskId(), adminService.reschedule(record.getTaskId(), delayMillis));
    }
}