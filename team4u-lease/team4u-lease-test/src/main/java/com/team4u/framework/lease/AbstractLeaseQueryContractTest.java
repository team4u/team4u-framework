package com.team4u.framework.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseGrant;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeaseQueryRequest;
import org.junit.Assert;
import org.junit.Test;

public abstract class AbstractLeaseQueryContractTest extends AbstractLeaseContractSupport {

    @Test
    public void testGetReturnsCurrentRecordSnapshot() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 100L, 200L);

        Assert.assertEquals(taskId, backend.get(taskId).get().getTaskId());
        Assert.assertEquals(LeaseTaskState.RUNNING, backend.get(taskId).get().getState());
        Assert.assertEquals("worker-a", backend.get(taskId).get().getWorkerId());

        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.close(
                grant.getHandle(), LeaseCloseRequest.failed(LeaseTaskFailureReason.HANDLER_EXCEPTION, "boom")));

        Assert.assertEquals(LeaseTaskState.CLOSED, backend.get(taskId).get().getState());
        Assert.assertEquals(LeaseTaskOutcome.FAILED, backend.get(taskId).get().getOutcome());
        Assert.assertEquals(LeaseTaskFailureReason.HANDLER_EXCEPTION, backend.get(taskId).get().getFailureReason());
    }

    @Test
    public void testListCanFilterByQueueTaskTypeAndState() {
        LeaseBackend backend = createBackend();
        backend.publish(LeasePublishRequest.builder().queue("queue-a").taskType("pay").payload("a").priority(5).build());
        backend.publish(LeasePublishRequest.builder().queue("queue-b").taskType("mail").payload("b").build());

        Assert.assertEquals(1, backend.list(LeaseQueryRequest.builder()
                .queue("queue-a")
                .taskType("pay")
                .state(LeaseTaskState.READY)
                .build()).getItems().size());
    }

    @Test
    public void testListCanFilterByOutcomeAndFailureReason() throws Exception {
        LeaseBackend backend = createBackend();
        String failedTaskId = publish(backend, "pay", "failed");
        String cancelledTaskId = publish(backend, "pay", "cancelled");

        LeaseGrant failedGrant = acquire(backend, "worker-a", 100L, 200L);
        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.close(
                failedGrant.getHandle(),
                LeaseCloseRequest.failed(LeaseTaskFailureReason.RETRY_EXHAUSTED, "retry exhausted")));
        Assert.assertEquals(com.team4u.framework.lease.enums.LeaseAdminResult.APPLIED,
                backend.close(cancelledTaskId, LeaseCloseRequest.cancelled("cancelled")));

        Assert.assertEquals(failedTaskId, backend.list(LeaseQueryRequest.builder()
                .outcome(LeaseTaskOutcome.FAILED)
                .failureReason(LeaseTaskFailureReason.RETRY_EXHAUSTED)
                .build()).getItems().get(0).getTaskId());

        Assert.assertEquals(cancelledTaskId, backend.list(LeaseQueryRequest.builder()
                .outcome(LeaseTaskOutcome.CANCELLED)
                .build()).getItems().get(0).getTaskId());
    }

    @Test
    public void testListCanFilterByWorkerIdForRunningTask() throws Exception {
        LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload");
        acquire(backend, "worker-a", 100L, 200L);

        Assert.assertEquals(1, backend.list(LeaseQueryRequest.builder()
                .workerId("worker-a")
                .state(LeaseTaskState.RUNNING)
                .build()).getItems().size());
    }
}
