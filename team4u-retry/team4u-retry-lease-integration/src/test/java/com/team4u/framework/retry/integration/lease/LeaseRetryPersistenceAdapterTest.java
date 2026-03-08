package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseAdminService;
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeaseUpdateRequest;
import com.team4u.framework.retry.backend.RetryCloseRequest;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class LeaseRetryPersistenceAdapterTest {

    private static RetryTaskSnapshot snapshot(String taskType, String payload) {
        RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
        snapshot.setTaskType(taskType);
        snapshot.setPayload(payload);
        return snapshot;
    }

    @Test
    public void saveShouldPublishPreparedIntentWhenTaskIdMissing() {
        AtomicReference<LeasePublishRequest> requestRef = new AtomicReference<>();
        LeaseProducer producer = request -> {
            requestRef.set(request);
            return "lease-task-1";
        };
        LeaseRetryBackend adapter = new LeaseRetryBackend(producer, new NoopAdminService(), "retry-q");
        RetryTaskSnapshot snapshot = snapshot("task-a", "{\"id\":1}");

        adapter.prepare(snapshot);

        Assert.assertEquals("lease-task-1", snapshot.getTaskId());
        Assert.assertEquals("retry-q", requestRef.get().getQueue());
        Assert.assertEquals("task-a", requestRef.get().getTaskType());
        // payload 现在是序列化后的完整快照 JSON，反序列化后验证原始业务载荷
        RetryTaskSnapshot published = com.team4u.framework.retry.backend.serialize.HutoolRetryTaskSnapshotSerializer.INSTANCE
                .deserialize(requestRef.get().getPayload());
        Assert.assertEquals("{\"id\":1}", published.getPayload());
        Assert.assertTrue(requestRef.get().getDelayMillis() > 24L * 60L * 60L * 1000L);
    }

    @Test
    public void saveShouldSkipPublishWhenTaskIdAlreadyExists() {
        AtomicReference<LeasePublishRequest> requestRef = new AtomicReference<>();
        LeaseProducer producer = request -> {
            requestRef.set(request);
            return "unexpected";
        };
        LeaseRetryBackend adapter = new LeaseRetryBackend(producer, new NoopAdminService());
        RetryTaskSnapshot snapshot = snapshot("task-a", "{\"id\":1}");
        snapshot.setTaskId("existing-id");

        adapter.prepare(snapshot);

        Assert.assertNull(requestRef.get());
        Assert.assertEquals("existing-id", snapshot.getTaskId());
    }

    @Test
    public void saveShouldRejectNullSnapshot() {
        LeaseRetryBackend adapter = new LeaseRetryBackend(request -> "unused", new NoopAdminService());

        try {
            adapter.prepare(null);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("snapshot"));
        }
    }

    @Test
    public void saveShouldRejectBlankTaskType() {
        LeaseRetryBackend adapter = new LeaseRetryBackend(request -> "unused", new NoopAdminService());
        RetryTaskSnapshot snapshot = snapshot("  ", "{\"id\":1}");

        try {
            adapter.prepare(snapshot);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("taskType"));
        }
    }

    @Test
    public void saveShouldRejectNullPayload() {
        LeaseRetryBackend adapter = new LeaseRetryBackend(request -> "unused", new NoopAdminService());
        RetryTaskSnapshot snapshot = snapshot("task-a", null);

        try {
            adapter.prepare(snapshot);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("payload"));
        }
    }

    @Test
    public void handoffShouldThrowWhenLeaseAdminRejectsOperation() {
        LeaseAdminService adminService = new StubAdminService(LeaseAdminResult.ACTIVE_LEASE_PRESENT,
                LeaseAdminResult.APPLIED);
        LeaseRetryBackend adapter = new LeaseRetryBackend(request -> "unused", adminService);

        try {
            adapter.handoff("task-1", 1000L);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("ACTIVE_LEASE_PRESENT"));
        }
    }

    @Test
    public void closeShouldThrowWhenLeaseAdminRejectsOperation() {
        LeaseAdminService adminService = new StubAdminService(LeaseAdminResult.APPLIED,
                LeaseAdminResult.TASK_NOT_FOUND);
        LeaseRetryBackend adapter = new LeaseRetryBackend(request -> "unused", adminService);

        try {
            adapter.close("task-1", RetryCloseRequest.succeeded());
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("TASK_NOT_FOUND"));
        }
    }

    private static final class NoopAdminService implements LeaseAdminService {
        @Override
        public LeaseAdminResult reschedule(String taskId, long delayMillis) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult update(LeaseUpdateRequest request) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult requeueFailed(String taskId, long delayMillis) {
            return LeaseAdminResult.APPLIED;
        }
    }

    private static final class StubAdminService implements LeaseAdminService {
        private final LeaseAdminResult rescheduleResult;
        private final LeaseAdminResult cancelResult;

        private StubAdminService(LeaseAdminResult rescheduleResult, LeaseAdminResult cancelResult) {
            this.rescheduleResult = rescheduleResult;
            this.cancelResult = cancelResult;
        }

        @Override
        public LeaseAdminResult reschedule(String taskId, long delayMillis) {
            return rescheduleResult;
        }

        @Override
        public LeaseAdminResult update(LeaseUpdateRequest request) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
            return cancelResult;
        }

        @Override
        public LeaseAdminResult requeueFailed(String taskId, long delayMillis) {
            return LeaseAdminResult.APPLIED;
        }
    }
}
