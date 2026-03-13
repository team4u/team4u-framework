package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseContractSupport;
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeaseQueryRequest;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class InMemoryLeaseBackendTest extends AbstractLeaseContractSupport {

    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }

    @Test
    public void testListCanFilterByQueueTaskTypeAndState() {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        backend.publish(
                LeasePublishRequest.builder().taskGroup("group-a").taskType("pay").payload("a").priority(5).build());
        backend.publish(LeasePublishRequest.builder().taskGroup("group-b").taskType("mail").payload("b").build());

        Assert.assertEquals(1, backend.list(LeaseQueryRequest.builder()
                .taskGroup("group-a")
                .taskType("pay")
                .state(LeaseTaskState.READY)
                .build()).getItems().size());
    }

    @Test
    public void testSnapshotReflectsInternalStoredTasks() {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        String taskId = backend.publish(LeasePublishRequest.builder()
                .taskGroup("group-a")
                .taskType("pay")
                .payload("a")
                .build());

        Map<String, InMemoryLeaseBackend.StoredTask> snapshot = backend.snapshot();
        Assert.assertTrue(snapshot.containsKey(taskId));
        Assert.assertEquals(LeaseTaskState.READY, snapshot.get(taskId).getState());
        Assert.assertEquals("group-a", snapshot.get(taskId).getTaskGroup());
        Assert.assertEquals("pay", snapshot.get(taskId).getTaskType());
    }
}
