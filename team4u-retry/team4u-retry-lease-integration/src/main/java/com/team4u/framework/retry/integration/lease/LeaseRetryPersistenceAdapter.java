package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseAdminService;
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.backend.RetryBackend;

/**
 * 基于 Lease 功能实现的重试持久化适配器
 * <p>
 * 将重试任务持久化到 Lease 存储中，并利用 Lease 的延迟发布特性实现退避重试。
 */
public class LeaseRetryPersistenceAdapter implements RetryBackend {

    private static final long PREPARED_INTENT_DELAY_MILLIS = 3650L * 24L * 60L * 60L * 1000L;

    private final LeaseProducer producer;
    private final LeaseAdminService adminService;
    private final String queue;

    public LeaseRetryPersistenceAdapter(LeaseBackend backend) {
        this(backend, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public LeaseRetryPersistenceAdapter(LeaseBackend backend, String queue) {
        this(backend, backend, queue);
    }

    public LeaseRetryPersistenceAdapter(LeaseProducer producer, LeaseAdminService adminService) {
        this(producer, adminService, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public LeaseRetryPersistenceAdapter(LeaseProducer producer, LeaseAdminService adminService, String queue) {
        this.producer = producer;
        this.adminService = adminService;
        this.queue = (queue == null || queue.trim().isEmpty()) ? RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE : queue;
    }

    @Override
    public void save(RetryTaskSnapshot snapshot) {
        validateSnapshot(snapshot);
        // 由于目前 Lease 并不支持 publish 时指定 ID 且不支持覆盖更新，
        // 在新模型下，save 主要承担 PREPARE_INTENT 阶段的“挂起”持久化。
        // 如果 snapshot 还没有 taskId（通常在第一次进入持久化层时），我们通过 publish 创建一个超长延迟的任务。
        if (snapshot.getTaskId() == null) {
            String taskId = producer.publish(LeasePublishRequest.builder()
                    .queue(queue)
                    .taskType(snapshot.getTaskType())
                    .payload(snapshot.getPayload())
                    .delayMillis(PREPARED_INTENT_DELAY_MILLIS)
                    .build());
            snapshot.setTaskId(taskId);
        }
        // 注意：Lease 场景下的状态更新通常是通过 handoff (reschedule) 完成的，
        // 这里的 save 仅处理初始化。如果需要支持运行中快照更新，需扩展 Lease 的自定义 ID 或 Update 接口。
    }

    @Override
    public void handoff(String taskId, long delayMillis) {
        assertTaskId(taskId, "handoff");
        assertApplied("handoff", taskId, adminService.reschedule(taskId, delayMillis));
    }

    @Override
    public void delete(String taskId) {
        assertTaskId(taskId, "delete");
        assertApplied("delete", taskId, adminService.cancel(taskId));
    }

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
}
