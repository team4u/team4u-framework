package com.team4u.framework.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.*;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseGrant;
import com.team4u.framework.lease.model.LeaseUpdateRequest;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

/**
 * 租约管理功能契约测试基类
 * <p>
 * 定义了租约管理接口（如重新调度、关闭、重新入队等）的标准行为规范。
 * 不同实现的后端需继承此类并提供具体的实例进行验证。
 */
public abstract class AbstractLeaseAdminContractTest extends AbstractLeaseContractSupport {

    @Test
    public void testRescheduleOverridesVisibleTime() throws Exception {
        LeaseBackend backend = createBackend();
        // [1] 发布一个 200ms 后可见的任务
        String taskId = publish(backend, "pay", "payload", 200L);

        // [2] 等待一段时间后，将其重新调度为 20ms 后可见
        Thread.sleep(30L);
        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.reschedule(taskId, 20L));
        // [3] 等待重新调度的延迟生效
        Thread.sleep(40L);

        LeaseGrant grant = acquire(backend, "worker-a", 100L, 200L);
        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getTaskId());
    }

    /**
     * 测试管理员手动关闭已取消的任务，确保任务状态更新为 CLOSED。
     */
    @Test
    public void testAdminCloseCancelledMarksTaskClosed() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");

        Assert.assertEquals(LeaseAdminResult.APPLIED,
                backend.close(taskId, LeaseCloseRequest.cancelled("cancelled")));

        // 验证任务无法再被获取
        Assert.assertNull(acquire(backend, "worker-a", 100L, 50L));
        // 验证任务详细状态
        Assert.assertEquals(LeaseTaskState.CLOSED, backend.get(taskId).get().getState());
        Assert.assertEquals(LeaseTaskOutcome.CANCELLED, backend.get(taskId).get().getOutcome());
        Assert.assertEquals("cancelled", backend.get(taskId).get().getErrorMessage());
    }

    @Test
    public void testAdminCloseRejectsActiveLease() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        acquire(backend, "worker-a", 100L, 200L);

        Assert.assertEquals(LeaseAdminResult.ACTIVE_LEASE_PRESENT,
                backend.close(taskId, LeaseCloseRequest.cancelled("cancelled")));
    }

    @Test
    public void testRequeueFailedOnlyAppliesToFailedTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 100L, 200L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.close(
                grant.getHandle(), LeaseCloseRequest.failed(LeaseTaskFailureReason.HANDLER_EXCEPTION, "boom")));
        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.requeueFailed(taskId, 10L));

        Thread.sleep(20L);
        LeaseGrant next = acquire(backend, "worker-b", 100L, 200L);
        Assert.assertNotNull(next);
        Assert.assertEquals(1, next.getFailureCount());
        Assert.assertEquals(2, next.getDeliveryCount());
        Assert.assertEquals(LeaseAdminResult.CLOSED, backend.requeueFailed(next.getTaskId(), 0L));
    }

    @Test
    public void testRequeueFailedRejectsCancelledTask() {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.close(taskId, LeaseCloseRequest.cancelled("cancelled")));
        Assert.assertEquals(LeaseAdminResult.CLOSED, backend.requeueFailed(taskId, 0L));
    }

    @Test
    public void testUpdateChangesTaskContent() {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .taskType("mail")
                .payload("changed")
                .priority(9)
                .attributes(Collections.singletonMap("traceId", "T-1"))
                .build()));

        Assert.assertEquals("mail", backend.get(taskId).get().getTaskType());
        Assert.assertEquals("changed", backend.get(taskId).get().getPayload());
        Assert.assertEquals(9, backend.get(taskId).get().getPriority());
        Assert.assertEquals("T-1", backend.get(taskId).get().getAttributes().get("traceId"));
    }

    @Test
    public void testUpdateRejectsActiveLease() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        acquire(backend, "worker-a", 200L, 200L);

        Assert.assertEquals(LeaseAdminResult.ACTIVE_LEASE_PRESENT, backend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .payload("changed")
                .build()));
        Assert.assertEquals("payload", backend.get(taskId).get().getPayload());
    }

    @Test
    public void testUpdateRejectsClosedTask() {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        Assert.assertEquals(LeaseAdminResult.APPLIED,
                backend.close(taskId, LeaseCloseRequest.cancelled("cancelled")));

        Assert.assertEquals(LeaseAdminResult.CLOSED, backend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .payload("changed")
                .build()));
        Assert.assertEquals("payload", backend.get(taskId).get().getPayload());
    }

    @Test
    public void testUpdateAllowsExpiredLeaseTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        acquire(backend, "worker-a", 30L, 100L);
        Thread.sleep(60L);

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .payload("changed")
                .build()));
        Assert.assertEquals("changed", backend.get(taskId).get().getPayload());
    }

    @Test
    public void testAdminOperationsReturnTaskNotFoundForMissingTask() {
        LeaseBackend backend = createBackend();

        Assert.assertEquals(LeaseAdminResult.TASK_NOT_FOUND, backend.reschedule("missing", 10L));
        Assert.assertEquals(LeaseAdminResult.TASK_NOT_FOUND,
                backend.close("missing", LeaseCloseRequest.cancelled("cancelled")));
        Assert.assertEquals(LeaseAdminResult.TASK_NOT_FOUND, backend.requeueFailed("missing", 10L));
        Assert.assertEquals(LeaseAdminResult.TASK_NOT_FOUND, backend.update(LeaseUpdateRequest.builder()
                .taskId("missing")
                .payload("x")
                .build()));
    }
}
