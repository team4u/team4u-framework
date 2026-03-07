package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.LeaseAdminService;
import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeaseProducer;
import com.team4u.framework.lease.LeasePublishRequest;
import com.team4u.framework.retry.backend.RetryBackend;

/**
 * 基于 Lease 功能实现的重试后端适配器
 * <p>
 * 将重试任务持久化到 Lease 存储中，并利用 Lease 的延迟发布特性实现退避重试。
 */
public class LeaseRetryBackend implements RetryBackend {

    private static final long PREPARED_INTENT_DELAY_MILLIS = 3650L * 24L * 60L * 60L * 1000L;

    private final LeaseProducer producer;
    private final LeaseAdminService adminService;
    private final String queue;

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

    @Override
    public String prepare(String taskType, String payload) {
        return producer.publish(LeasePublishRequest.builder()
                .queue(queue)
                .taskType(taskType)
                .payload(payload)
                .delayMillis(PREPARED_INTENT_DELAY_MILLIS)
                .build());
    }

    @Override
    public void handoff(String intentId, long delayMillis) {
        adminService.reschedule(intentId, delayMillis);
    }

    @Override
    public void complete(String intentId) {
        adminService.cancel(intentId);
    }
}