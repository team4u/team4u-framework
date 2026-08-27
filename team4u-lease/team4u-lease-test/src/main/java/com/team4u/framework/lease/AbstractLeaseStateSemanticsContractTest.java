package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.RetryCommand;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.UpdateCommand;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public abstract class AbstractLeaseStateSemanticsContractTest extends AbstractLeaseContractSupport {

    @Test
    public void testSuccessCompletionMovesRunningTaskToSucceeded() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v1", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.succeeded("payload-v2", Collections.singletonMap("traceId", "T-2"))));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.SUCCEEDED, snapshot.getStatus());
        Assert.assertNull(snapshot.getWorkerId());
        Assert.assertNull(snapshot.getLeaseExpiresAt());
        Assert.assertEquals("payload-v2", snapshot.getPayload());
        Assert.assertNull(snapshot.getErrorMessage());
        Assert.assertEquals("T-2", snapshot.getAttributes().get("traceId"));
        Assert.assertEquals(1, snapshot.getAttemptCount());
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS));
    }

    @Test
    public void testSuccessWithoutPatchKeepsPayloadAndAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.succeeded(null, null)));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals("payload", snapshot.getPayload());
        Assert.assertEquals("T-1", snapshot.getAttributes().get("traceId"));
        Assert.assertNull(snapshot.getErrorMessage());
    }

    @Test
    public void testSuccessWithEmptyAttributePatchClearsAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.succeeded(null, Collections.<String, String>emptyMap())));

        Assert.assertTrue(snapshot(backend, DEFAULT_QUEUE, taskId).getAttributes().isEmpty());
    }

    @Test
    public void testFailureCompletionMovesRunningTaskToFailed() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v1", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.failed("boom", "payload-v2", Collections.singletonMap("traceId", "T-2"))));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.FAILED, snapshot.getStatus());
        Assert.assertNull(snapshot.getWorkerId());
        Assert.assertNull(snapshot.getLeaseExpiresAt());
        Assert.assertEquals("boom", snapshot.getErrorMessage());
        Assert.assertEquals("payload-v2", snapshot.getPayload());
        Assert.assertEquals("T-2", snapshot.getAttributes().get("traceId"));
        Assert.assertEquals(1, snapshot.getAttemptCount());
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS));
    }

    @Test
    public void testFailureWithoutAttributePatchKeepsAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.failed("boom", null, null)));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals("payload", snapshot.getPayload());
        Assert.assertEquals("T-1", snapshot.getAttributes().get("traceId"));
        Assert.assertEquals("boom", snapshot.getErrorMessage());
    }

    @Test
    public void testFailureWithEmptyAttributePatchClearsAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.failed("boom", null, Collections.<String, String>emptyMap())));

        Assert.assertTrue(snapshot(backend, DEFAULT_QUEUE, taskId).getAttributes().isEmpty());
    }

    @Test
    public void testCancelMovesPendingTaskToCancelled() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v1", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled("cancelled", null, null))));
        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.CANCELLED, snapshot.getStatus());
        Assert.assertNull(snapshot.getWorkerId());
        Assert.assertNull(snapshot.getLeaseExpiresAt());
        Assert.assertEquals("cancelled", snapshot.getErrorMessage());
        Assert.assertEquals("payload-v1", snapshot.getPayload());
        Assert.assertEquals("T-1", snapshot.getAttributes().get("traceId"));
        Assert.assertEquals(0, snapshot.getAttemptCount());
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));
    }

    @Test
    public void testCancelKeepsErrorMessage() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled("cancelled", null, null))));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals("payload", snapshot.getPayload());
        Assert.assertEquals("cancelled", snapshot.getErrorMessage());
    }

    @Test
    public void testTerminalTaskRejectsRuntimeCompletion() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.succeeded(null, null)));
        Assert.assertEquals(RuntimeResult.TERMINAL, backend.close(grant.getHandle(),
                LeaseCompletion.failed("boom", null, null)));
        Assert.assertEquals(RuntimeResult.TERMINAL, backend.heartbeat(grant.getHandle(), LONG_LEASE_MILLIS));
        Assert.assertEquals(RuntimeResult.TERMINAL, backend.release(grant.getHandle(),
                LeaseRetry.of(0L, null, null, null)));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.SUCCEEDED, snapshot.getStatus());
        Assert.assertEquals(1, snapshot.getAttemptCount());
    }

    @Test
    public void testRetryKeepsTaskPendingAndIncrementsAttemptOnNextAcquire() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(grant.getHandle(),
                LeaseRetry.of(0L, "payload-v2", "retry", Collections.singletonMap("traceId", "T-2"))));

        TaskSnapshot pending = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, pending.getStatus());
        Assert.assertNull(pending.getWorkerId());
        Assert.assertNull(pending.getLeaseExpiresAt());
        Assert.assertEquals("payload-v2", pending.getPayload());
        Assert.assertEquals("retry", pending.getErrorMessage());
        Assert.assertEquals("T-2", pending.getAttributes().get("traceId"));

        LeaseGrant next = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS),
                taskId, WORKER_B);
        Assert.assertEquals(2, next.getSnapshot().getAttemptCount());
    }

    @Test
    public void testRuntimeRetryWithNullErrorClearsPreviousError() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant first = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(first.getHandle(),
                LeaseRetry.of(0L, null, "boom", null)));

        waitUntilAfter(snapshot(backend, DEFAULT_QUEUE, taskId).getVisibleAt());
        LeaseGrant second = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS),
                taskId, WORKER_B);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(second.getHandle(),
                LeaseRetry.of(LONG_LEASE_MILLIS, null, null, null)));

        TaskSnapshot pending = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, pending.getStatus());
        Assert.assertNull(pending.getErrorMessage());
    }

    @Test
    public void testSuccessCompletionClearsPreviousError() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant failed = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(failed.getHandle(),
                LeaseCompletion.failed("boom", null, null)));
        Assert.assertEquals(AdminResult.APPLIED, backend.retry(
                RetryCommand.of(DEFAULT_QUEUE, taskId, SHORT_DELAY_MILLIS)));

        waitUntilAfter(snapshot(backend, DEFAULT_QUEUE, taskId).getVisibleAt());
        LeaseGrant retried = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS),
                taskId, WORKER_B);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(retried.getHandle(),
                LeaseCompletion.succeeded(null, null)));

        TaskSnapshot succeeded = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.SUCCEEDED, succeeded.getStatus());
        Assert.assertNull(succeeded.getErrorMessage());
    }

    @Test
    public void testRescheduleAndAdminRetryStartNewCycleWithoutError() throws Exception {
        LeaseBackend backend = createBackend();
        String rescheduledTaskId = submit(backend, PAY_TASK_TYPE, "reschedule-payload");
        LeaseGrant retrying = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                rescheduledTaskId, WORKER_A);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(retrying.getHandle(),
                LeaseRetry.of(0L, null, "boom", null)));
        Assert.assertEquals(AdminResult.APPLIED, backend.reschedule(RescheduleCommand.of(
                DEFAULT_QUEUE, rescheduledTaskId, LONG_LEASE_MILLIS)));
        Assert.assertNull(snapshot(backend, DEFAULT_QUEUE, rescheduledTaskId).getErrorMessage());

        String retriedTaskId = submit(backend, MAIL_TASK_TYPE, "retry-payload");
        LeaseGrant failed = assertRunningGrant(acquire(backend, MAIL_TASK_TYPE, WORKER_B, LEASE_MILLIS),
                retriedTaskId, WORKER_B);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(failed.getHandle(),
                LeaseCompletion.failed("boom", null, null)));
        Assert.assertEquals(AdminResult.APPLIED, backend.retry(RetryCommand.of(
                DEFAULT_QUEUE, retriedTaskId, LONG_LEASE_MILLIS)));
        Assert.assertNull(snapshot(backend, DEFAULT_QUEUE, retriedTaskId).getErrorMessage());
    }

    @Test
    public void testUpdateAndRescheduleClearsErrorWhileMetadataUpdateKeepsIt() throws Exception {
        LeaseBackend backend = createBackend();
        String updatedTaskId = submit(backend, PAY_TASK_TYPE, "update-payload");
        LeaseGrant updateGrant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                updatedTaskId, WORKER_A);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(updateGrant.getHandle(),
                LeaseRetry.of(0L, null, "boom", null)));

        Assert.assertEquals(AdminResult.APPLIED, backend.update(UpdateCommand.of(
                DEFAULT_QUEUE, updatedTaskId, null, "changed", null, null, false, null)));
        TaskSnapshot metadataUpdated = snapshot(backend, DEFAULT_QUEUE, updatedTaskId);
        Assert.assertEquals(TaskStatus.PENDING, metadataUpdated.getStatus());
        Assert.assertEquals("changed", metadataUpdated.getPayload());
        Assert.assertEquals("boom", metadataUpdated.getErrorMessage());

        String rescheduledTaskId = submit(backend, MAIL_TASK_TYPE, "reschedule-payload");
        LeaseGrant rescheduleGrant = assertRunningGrant(acquire(backend, MAIL_TASK_TYPE, WORKER_B, LEASE_MILLIS),
                rescheduledTaskId, WORKER_B);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(rescheduleGrant.getHandle(),
                LeaseRetry.of(0L, null, "boom", null)));
        Assert.assertEquals(AdminResult.APPLIED, backend.updateAndReschedule(UpdateCommand.of(
                DEFAULT_QUEUE, rescheduledTaskId, null, "changed", null, null, false, Long.valueOf(0L))));

        TaskSnapshot rescheduled = snapshot(backend, DEFAULT_QUEUE, rescheduledTaskId);
        Assert.assertEquals(TaskStatus.PENDING, rescheduled.getStatus());
        Assert.assertEquals("changed", rescheduled.getPayload());
        Assert.assertNull(rescheduled.getErrorMessage());
        assertRunningGrant(acquire(backend, MAIL_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS),
                rescheduledTaskId, WORKER_A);
    }
}
