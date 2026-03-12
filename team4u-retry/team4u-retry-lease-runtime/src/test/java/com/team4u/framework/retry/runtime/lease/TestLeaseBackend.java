package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.model.*;

import java.util.Optional;
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

    public abstract void submitForDelay(String intentId, String taskType, String payload, long delay);

    @Override
    public String publish(LeasePublishRequest request) {
        String taskType = request == null ? null : request.getTaskType();
        String payload = request == null ? null : request.getPayload();
        long delayMillis = request == null ? 0L : request.getDelayMillis();
        if (delayMillis >= PREPARE_THRESHOLD_MILLIS) {
            return saveIntent(taskType, payload);
        }
        String taskId = "lease-test-" + UUID.randomUUID().toString().replace("-", "");
        submitForDelay(taskId, taskType, payload, delayMillis);
        return taskId;
    }

    @Override
    public LeaseAdminResult reschedule(String taskId, long delayMillis) {
        submitForDelay(taskId, null, null, delayMillis);
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
        completeIntent(taskId);
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public LeaseAdminResult requeueFailed(String taskId, long delayMillis) {
        submitForDelay(taskId, null, null, delayMillis);
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public LeaseAdminResult update(LeaseUpdateRequest request) {
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public LeaseAdminResult updateAndReschedule(LeaseUpdateRequest request, long delayMillis) {
        submitForDelay(request.getTaskId(), request.getTaskType(), request.getPayload(), delayMillis);
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public LeaseGrant acquire(LeaseAcquireRequest request) {
        return null;
    }

    @Override
    public LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request) {
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public Optional<LeaseTaskRecord> get(String taskId) {
        return Optional.empty();
    }

    @Override
    public LeasePublishResult publishIfAbsent(LeasePublishRequest request) {
        String taskId = publish(request);
        return LeasePublishResult.builder()
                .created(true)
                .taskId(taskId)
                .build();
    }

    @Override
    public Optional<LeaseTaskRecord> getByBusinessKey(String queue, String businessKey) {
        return Optional.empty();
    }

    @Override
    public LeaseTaskPage list(LeaseQueryRequest request) {
        return LeaseTaskPage.builder().total(0).page(0).pageSize(0).build();
    }
}
