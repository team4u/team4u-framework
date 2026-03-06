package com.team4u.framework.retry;

import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeaseGrant;

import java.util.UUID;

/**
 * 测试辅助实现基类。
 * <p>
 * 该类通过装饰器模式，帮助 retry 模块的单元测试在迁移到 {@link LeaseBackend} 后，
 * 能够继续通过旧有的业务语义（如 saveIntent, completeIntent）进行断言，降低迁移成本。
 */
public abstract class TestLeaseBackend implements LeaseBackend {

    private static final long PREPARE_THRESHOLD_MILLIS = 24L * 60L * 60L * 1000L;

    public abstract String saveIntent(String taskType, String payload);

    public abstract void completeIntent(String intentId);

    public abstract void markTerminalFailure(String intentId, Throwable cause);

    public abstract void submitForDelay(String intentId, String taskType, String payload, long delay);

    @Override
    public String publish(String taskType, String payload) {
        return publish(taskType, payload, 0L);
    }

    @Override
    public String publish(String taskType, String payload, long delayMillis) {
        if (delayMillis >= PREPARE_THRESHOLD_MILLIS) {
            return saveIntent(taskType, payload);
        }
        String taskId = "lease-test-" + UUID.randomUUID().toString().replace("-", "");
        submitForDelay(taskId, taskType, payload, delayMillis);
        return taskId;
    }

    @Override
    public void reschedule(String taskId, long delayMillis) {
        submitForDelay(taskId, null, null, delayMillis);
    }

    @Override
    public void cancel(String taskId) {
        completeIntent(taskId);
    }

    @Override
    public LeaseGrant acquire(String workerId, long leaseMillis, long waitTimeoutMillis) {
        return null;
    }

    @Override
    public void ack(String taskId, String workerId, String leaseToken) {
    }

    @Override
    public void retry(String taskId, String workerId, String leaseToken, long delayMillis, Throwable cause) {
    }

    @Override
    public void fail(String taskId, String workerId, String leaseToken, Throwable cause) {
    }

    @Override
    public void heartbeat(String taskId, String workerId, String leaseToken, long extendMillis) {
    }
}
