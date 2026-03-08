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
import com.team4u.framework.retry.backend.*;
import com.team4u.framework.retry.backend.serialize.HutoolRetryTaskSnapshotSerializer;
import com.team4u.framework.retry.backend.serialize.RetryTaskSnapshotSerializer;
import lombok.Setter;

/**
 * 基于 Lease 功能实现的重试持久化适配器
 * <p>
 * 将重试任务持久化到 Lease 存储中，并利用 Lease 的延迟发布特性实现退避重试。
 */
public class LeaseRetryBackend implements RetryBackend {

    private static final long PREPARED_INTENT_DELAY_MILLIS = 3650L * 24L * 60L * 60L * 1000L;

    private final LeaseProducer producer;
    private final LeaseAdminService adminService;
    private final String queue;

    @Setter
    private RetryTaskSnapshotSerializer snapshotSerializer = HutoolRetryTaskSnapshotSerializer.INSTANCE;

    public LeaseRetryBackend(LeaseBackend backend) {
        this(backend, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public LeaseRetryBackend(LeaseBackend backend, String queue) {
        this(backend, backend, queue);
    }

    public LeaseRetryBackend(LeaseProducer producer, LeaseAdminService adminService) {
        this(producer, adminService, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public LeaseRetryBackend(LeaseProducer producer, LeaseAdminService adminService, String queue) {
        this.producer = producer;
        this.adminService = adminService;
        this.queue = (queue == null || queue.trim().isEmpty()) ? RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE : queue;
    }

    /**
     * 校验重试任务快照的有效性
     *
     * @param snapshot 任务快照
     */
    private static void validateSnapshot(RetryTaskSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (snapshot.getTaskType() == null || snapshot.getTaskType().trim().isEmpty()) {
            throw new IllegalArgumentException("snapshot.taskType must not be blank");
        }
        if (snapshot.getPayload() == null) {
            throw new IllegalArgumentException("snapshot.payload must not be null");
        }
    }

    private static void assertTaskId(String taskId, String operation) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException(operation + " taskId must not be blank");
        }
    }

    private static void assertApplied(String operation, String taskId, LeaseAdminResult result) {
        if (result != LeaseAdminResult.APPLIED) {
            throw new IllegalStateException(
                    "Lease " + operation + " was not applied for taskId=" + taskId + ", result=" + result);
        }
    }

    @Override
    public void prepare(RetryTaskSnapshot snapshot) {
        validateSnapshot(snapshot);

        if (snapshot.getTaskId() == null) {
            String taskId = producer.publish(LeasePublishRequest.builder()
                    .queue(queue)
                    .taskType(snapshot.getTaskType())
                    .payload(snapshotSerializer.serialize(snapshot))
                    .delayMillis(PREPARED_INTENT_DELAY_MILLIS)
                    .build());
            snapshot.setTaskId(taskId);
        } else {
            saveProgress(snapshot);
        }
    }

    @Override
    public void handoff(String taskId, long delayMillis) {
        assertTaskId(taskId, "handoff");
        assertApplied("handoff", taskId, adminService.reschedule(taskId, delayMillis));
    }

    @Override
    public void saveProgress(RetryTaskSnapshot snapshot) {
        assertTaskId(snapshot.getTaskId(), "saveProgress");

        assertApplied("saveProgress", snapshot.getTaskId(), adminService.update(
                LeaseUpdateRequest.builder()
                        .taskId(snapshot.getTaskId())
                        .payload(snapshotSerializer.serialize(snapshot))
                        .build()));
    }

    @Override
    public void close(String taskId, RetryCloseRequest request) {
        assertTaskId(taskId, "close");
        LeaseCloseRequest closeRequest = LeaseCloseRequest.builder()
                .outcome(mapOutcome(request == null ? null : request.getOutcome()))
                .failureReason(mapReason(request == null ? null : request.getReason()))
                .errorMessage(request == null ? null : request.getErrorMessage())
                .build();
        assertApplied("close", taskId, adminService.close(taskId, closeRequest));
    }

    /**
     * 将重试系统的执行结果映射为租约任务的最终结果
     *
     * @param outcome 重试任务结束结果
     * @return 租约任务结果
     */
    private LeaseTaskOutcome mapOutcome(RetryCloseOutcome outcome) {
        if (outcome == null) {
            return LeaseTaskOutcome.CANCELLED;
        }
        switch (outcome) {
            case SUCCEEDED:
                return LeaseTaskOutcome.SUCCEEDED;
            case FAILED:
                return LeaseTaskOutcome.FAILED;
            case CANCELLED:
            default:
                return LeaseTaskOutcome.CANCELLED;
        }
    }

    /**
     * 将重试系统的终止原因映射为租约任务的失败原因
     *
     * @param reason 重试任务终止原因
     * @return 租约任务失败原因
     */
    private LeaseTaskFailureReason mapReason(RetryCloseReason reason) {
        if (reason == null) {
            return null;
        }
        switch (reason) {
            case RETRY_EXHAUSTED:
                return LeaseTaskFailureReason.RETRY_EXHAUSTED;
            case ABORTED_BY_POLICY:
            default:
                return LeaseTaskFailureReason.ABORTED_BY_POLICY;
        }
    }
}
