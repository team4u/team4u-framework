package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseBackendContractTest;
import com.team4u.framework.lease.LeaseAdminResult;
import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeaseGrant;
import com.team4u.framework.lease.LeasePublishRequest;
import com.team4u.framework.lease.LeaseQueryRequest;
import com.team4u.framework.lease.LeaseRuntimeResult;
import com.team4u.framework.lease.LeaseTaskStatus;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class InMemoryLeaseBackendTest extends AbstractLeaseBackendContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }

    @Test
    public void testRescheduleOverridesVisibleTime() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        String taskId = publish(backend, "pay", "payload", 200L);

        Thread.sleep(30L);
        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.reschedule(taskId, 20L));
        Thread.sleep(40L);

        LeaseGrant grant = acquire(backend, "worker-a", 100L, 200L, "pay");
        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getTaskId());
    }

    @Test
    public void testCancelMarksTaskDead() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        String taskId = publish(backend, "pay", "payload");

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.cancel(taskId));

        Assert.assertNull(acquire(backend, "worker-a", 100L, 50L, "pay"));
        Map<String, InMemoryLeaseBackend.StoredTask> snapshot = backend.snapshot();
        Assert.assertEquals(LeaseTaskStatus.DEAD, snapshot.get(taskId).getStatus());
        Assert.assertEquals("cancelled", snapshot.get(taskId).getLastError());
    }

    @Test
    public void testAckClearsPreviousLastError() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        String taskId = publish(backend, "pay", "payload");

        acquire(backend, "worker-a", 100L, 200L, "pay");
        Assert.assertEquals(LeaseRuntimeResult.APPLIED,
                backend.retry(taskId, "worker-a", backend.snapshot().get(taskId).getLeaseToken(),
                        10L, new IllegalStateException("boom")));

        Thread.sleep(20L);
        acquire(backend, "worker-a", 100L, 200L, "pay");
        Assert.assertEquals(LeaseRuntimeResult.APPLIED,
                backend.ack(taskId, "worker-a", backend.snapshot().get(taskId).getLeaseToken()));

        Map<String, InMemoryLeaseBackend.StoredTask> snapshot = backend.snapshot();
        Assert.assertEquals(LeaseTaskStatus.SUCCEEDED, snapshot.get(taskId).getStatus());
        Assert.assertNull(snapshot.get(taskId).getLastError());
    }

    @Test
    public void testCancelRejectsActiveLease() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        String taskId = publish(backend, "pay", "payload");
        acquire(backend, "worker-a", 100L, 200L, "pay");

        Assert.assertEquals(LeaseAdminResult.ACTIVE_LEASE_PRESENT, backend.cancel(taskId));
    }

    @Test
    public void testRequeueDeadOnlyAppliesToDeadTask() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        String taskId = publish(backend, "pay", "payload");
        acquire(backend, "worker-a", 100L, 200L, "pay");

        Assert.assertEquals(LeaseRuntimeResult.APPLIED,
                backend.fail(taskId, "worker-a", backend.snapshot().get(taskId).getLeaseToken(),
                        new IllegalStateException("boom")));
        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.requeueDead(taskId, 10L));

        Thread.sleep(20L);
        LeaseGrant next = acquire(backend, "worker-b", 100L, 200L, "pay");
        Assert.assertNotNull(next);
        Assert.assertEquals(1, next.getFailureCount());
        Assert.assertEquals(2, next.getDeliveryCount());
        Assert.assertEquals(LeaseAdminResult.TERMINAL, backend.requeueDead(next.getTaskId(), 0L));
    }

    @Test
    public void testListCanFilterByQueueTaskTypeAndStatus() {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        backend.publish(LeasePublishRequest.builder().queue("queue-a").taskType("pay").payload("a").priority(5).build());
        backend.publish(LeasePublishRequest.builder().queue("queue-b").taskType("mail").payload("b").build());

        Assert.assertEquals(1, backend.list(LeaseQueryRequest.builder()
                .queue("queue-a")
                .taskType("pay")
                .status(LeaseTaskStatus.SCHEDULED)
                .build()).getItems().size());
    }
}
