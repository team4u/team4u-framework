package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseBackendContractTest;
import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeaseGrant;
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
        String taskId = backend.publish("pay", "payload", 200L);

        Thread.sleep(30L);
        backend.reschedule(taskId, 20L);
        Thread.sleep(40L);

        LeaseGrant grant = backend.acquire("worker-a", 100L, 200L);
        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getTaskId());
    }

    @Test
    public void testCancelMarksTaskDead() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        String taskId = backend.publish("pay", "payload");

        backend.cancel(taskId);

        Assert.assertNull(backend.acquire("worker-a", 100L, 50L));
        Map<String, InMemoryLeaseBackend.StoredTask> snapshot = backend.snapshot();
        Assert.assertEquals(LeaseTaskStatus.DEAD, snapshot.get(taskId).getStatus());
        Assert.assertEquals("cancelled", snapshot.get(taskId).getLastError());
    }
}
