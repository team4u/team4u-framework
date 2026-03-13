package com.team4u.framework.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.*;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseGrant;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import org.junit.Assert;
import org.junit.Test;

/**
 * 租约状态语义契约测试基类
 * <p>
 * 验证租约在不同操作（如关闭、释放、重新入队）下的状态流转及属性变化的正确性。
 */
public abstract class AbstractLeaseStateSemanticsContractTest extends AbstractLeaseContractSupport {

    /**
     * 测试成功关闭任务后，是否正确清除了之前的错误信息。
     */
    @Test
    public void testCloseSuccessClearsPreviousErrorMessage() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");

        // [1] 模拟任务第一次执行失败
        LeaseGrant firstGrant = acquire(backend, "worker-a", 100L, 200L);
        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.close(
                firstGrant.getHandle(), LeaseCloseRequest.failed(LeaseTaskFailureReason.HANDLER_EXCEPTION, "boom")));
        // [2] 管理员将其重新入队
        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.rescheduleFailed(taskId, 0L));

        Thread.sleep(20L);
        // [3] 模拟任务第二次执行成功
        LeaseGrant secondGrant = acquire(backend, "worker-a", 100L, 200L);
        Assert.assertEquals(LeaseRuntimeResult.APPLIED,
                backend.close(secondGrant.getHandle(), LeaseCloseRequest.succeeded()));

        // [4] 验证最终状态：状态为 CLOSED，结果为 SUCCESS，且错误信息被清除
        Assert.assertEquals(LeaseTaskState.CLOSED, backend.get(taskId).get().getState());
        Assert.assertEquals(LeaseTaskOutcome.SUCCEEDED, backend.get(taskId).get().getOutcome());
        Assert.assertNull(backend.get(taskId).get().getFailureReason());
        Assert.assertNull(backend.get(taskId).get().getErrorMessage());
    }

    @Test
    public void testCloseFailedIncrementsFailureCount() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 100L, 200L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.close(
                grant.getHandle(), LeaseCloseRequest.failed(LeaseTaskFailureReason.HANDLER_EXCEPTION, "boom")));

        Assert.assertEquals(1, backend.get(taskId).get().getFailureCount());
        Assert.assertEquals(LeaseTaskOutcome.FAILED, backend.get(taskId).get().getOutcome());
    }

    @Test
    public void testCloseCancelledDoesNotIncrementFailureCount() {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.close(taskId, LeaseCloseRequest.cancelled("cancelled")));

        Assert.assertEquals(0, backend.get(taskId).get().getFailureCount());
        Assert.assertEquals(LeaseTaskOutcome.CANCELLED, backend.get(taskId).get().getOutcome());
    }

    @Test
    public void testReleasePersistsErrorMessageButDoesNotCloseTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 100L, 200L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED,
                backend.release(grant.getHandle(), LeaseReleaseRequest.of(0L, "retry later")));

        Assert.assertEquals(LeaseTaskState.READY, backend.get(taskId).get().getState());
        Assert.assertNull(backend.get(taskId).get().getOutcome());
        Assert.assertEquals("retry later", backend.get(taskId).get().getErrorMessage());
        Assert.assertEquals(0, backend.get(taskId).get().getFailureCount());
    }

    @Test
    public void testRequeueFailedClearsTerminalFieldsAndLeaseFields() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 100L, 200L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.close(
                grant.getHandle(), LeaseCloseRequest.failed(LeaseTaskFailureReason.RETRY_EXHAUSTED, "boom")));
        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.rescheduleFailed(taskId, 0L));

        Assert.assertEquals(LeaseTaskState.READY, backend.get(taskId).get().getState());
        Assert.assertNull(backend.get(taskId).get().getOutcome());
        Assert.assertNull(backend.get(taskId).get().getFailureReason());
        Assert.assertNull(backend.get(taskId).get().getWorkerId());
        Assert.assertEquals(0L, backend.get(taskId).get().getLeaseExpiresAtMillis());
    }
}
