package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.RetryCommand;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.UpdateCommand;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.Collections;

public abstract class AbstractLeaseAdminContractTest extends AbstractLeaseContractSupport {

    @Test
    public void testCompleteIsScopedByQueue() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String ordersTaskId = submit(backend, PAY_TASK_TYPE, "orders-payload");
        String invoicesTaskId = submit(backend, "invoices", PAY_TASK_TYPE, "invoices-payload");

        Assert.assertEquals(AdminResult.TASK_NOT_FOUND, backend.complete(AdminCompletionCommand.of(
                "invoices", ordersTaskId, LeaseCompletion.cancelled("wrong queue", null, null))));

        TaskSnapshot orders = snapshot(backend, DEFAULT_QUEUE, ordersTaskId);
        Assert.assertEquals(TaskStatus.PENDING, orders.getStatus());
        Assert.assertEquals(TaskStatus.PENDING, snapshot(backend, "invoices", invoicesTaskId).getStatus());
        Assert.assertNotNull(acquireFromQueue(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));
        Assert.assertNotNull(acquireFromQueue(backend, "invoices", PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS));
    }

    @Test
    public void testCompleteMovesPendingTaskToCancelled() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled("not needed", null, null))));
        Assert.assertEquals(TaskStatus.CANCELLED, snapshot(backend, DEFAULT_QUEUE, taskId).getStatus());
        Assert.assertEquals("not needed", snapshot(backend, DEFAULT_QUEUE, taskId).getErrorMessage());
    }

    @Test
    public void testCompleteMovesPendingTaskToSucceededAndPatchesFields() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v1", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.succeeded(
                        "payload-v2", Collections.singletonMap("traceId", "T-2")))));

        TaskSnapshot completed = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.SUCCEEDED, completed.getStatus());
        Assert.assertEquals("payload-v2", completed.getPayload());
        Assert.assertNull(completed.getErrorMessage());
        Assert.assertEquals("T-2", completed.getAttributes().get("traceId"));
    }

    @Test
    public void testCompleteMovesPendingTaskToFailedAndPatchesFields() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v1", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.failed(
                        "boom", "payload-v2", Collections.singletonMap("traceId", "T-2")))));

        TaskSnapshot completed = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.FAILED, completed.getStatus());
        Assert.assertEquals("payload-v2", completed.getPayload());
        Assert.assertEquals("boom", completed.getErrorMessage());
        Assert.assertEquals("T-2", completed.getAttributes().get("traceId"));
    }

    @Test
    public void testCompleteMovesPendingTaskToCancelledAndPatchesFields() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v1", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled(
                        "cancelled", "payload-v2", Collections.singletonMap("traceId", "T-2")))));

        TaskSnapshot completed = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.CANCELLED, completed.getStatus());
        Assert.assertEquals("payload-v2", completed.getPayload());
        Assert.assertEquals("cancelled", completed.getErrorMessage());
        Assert.assertEquals("T-2", completed.getAttributes().get("traceId"));
    }

    @Test
    public void testCompleteWithoutAttributePatchKeepsAttributes() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.failed("boom", null, null))));

        TaskSnapshot completed = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals("payload", completed.getPayload());
        Assert.assertEquals("boom", completed.getErrorMessage());
        Assert.assertEquals("T-1", completed.getAttributes().get("traceId"));
    }

    @Test
    public void testCompleteWithEmptyAttributePatchClearsAttributes() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.failed(
                        "boom", null, Collections.<String, String>emptyMap()))));

        Assert.assertTrue(snapshot(backend, DEFAULT_QUEUE, taskId).getAttributes().isEmpty());
    }

    @Test
    public void testCompleteRejectsActiveLease() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS), taskId, WORKER_A);

        Assert.assertEquals(AdminResult.ACTIVE_LEASE_PRESENT, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled("not needed", null, null))));
        Assert.assertEquals(TaskStatus.RUNNING, snapshot(backend, DEFAULT_QUEUE, taskId).getStatus());
    }

    @Test
    public void testCompleteAllowsExpiredLease() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        waitUntilAfter(grant.getSnapshot().getLeaseExpiresAt());
        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled("not needed", null, null))));
        TaskSnapshot completed = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.CANCELLED, completed.getStatus());
        Assert.assertNull(completed.getWorkerId());
        Assert.assertNull(completed.getLeaseExpiresAt());
    }

    @Test
    public void testCompleteRejectsTerminalTask() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled("first", null, null))));
        Assert.assertEquals(AdminResult.TERMINAL, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled("second", null, null))));
        Assert.assertEquals("first", snapshot(backend, DEFAULT_QUEUE, taskId).getErrorMessage());
    }

    @Test
    public void testRescheduleMovesPendingVisibleTimeForward() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null,
                LONG_LEASE_MILLIS, Collections.<String, String>emptyMap());
        TaskSnapshot pending = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertTrue(pending.getVisibleAt().isAfter(Instant.now()));
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));
        Assert.assertEquals(AdminResult.APPLIED, backend.reschedule(RescheduleCommand.of(
                DEFAULT_QUEUE, taskId, 0L)));

        TaskSnapshot rescheduled = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, rescheduled.getStatus());
        Assert.assertTrue(rescheduled.getVisibleAt().isBefore(pending.getVisibleAt()));
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS), taskId, WORKER_A);
    }

    @Test
    public void testRescheduleMovesVisibleTimeLater() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        TaskSnapshot pending = snapshot(backend, DEFAULT_QUEUE, taskId);

        Assert.assertEquals(AdminResult.APPLIED, backend.reschedule(RescheduleCommand.of(
                DEFAULT_QUEUE, taskId, LONG_LEASE_MILLIS)));

        TaskSnapshot rescheduled = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, rescheduled.getStatus());
        Assert.assertTrue(rescheduled.getVisibleAt().isAfter(pending.getVisibleAt()));
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));
    }

    @Test
    public void testRescheduleRejectsActiveLeaseAndTerminalTask() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String activeTaskId = submit(backend, PAY_TASK_TYPE, "active");
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS), activeTaskId, WORKER_A);
        String terminalTaskId = submit(backend, MAIL_TASK_TYPE, "terminal");
        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, terminalTaskId, LeaseCompletion.cancelled("first", null, null))));

        Assert.assertEquals(AdminResult.ACTIVE_LEASE_PRESENT, backend.reschedule(RescheduleCommand.of(
                DEFAULT_QUEUE, activeTaskId, 0L)));
        Assert.assertEquals(AdminResult.TERMINAL, backend.reschedule(RescheduleCommand.of(
                DEFAULT_QUEUE, terminalTaskId, 0L)));
    }

    @Test
    public void testRetryMovesFailedTaskBackToPending() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.failed("boom", "failed-payload", null)));

        Assert.assertEquals(AdminResult.APPLIED, backend.retry(RetryCommand.of(
                DEFAULT_QUEUE, taskId, LONG_LEASE_MILLIS)));

        TaskSnapshot pending = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, pending.getStatus());
        Assert.assertNull(pending.getWorkerId());
        Assert.assertNull(pending.getLeaseExpiresAt());
        Assert.assertTrue(pending.getVisibleAt().isAfter(Instant.now()));
        Assert.assertEquals("failed-payload", pending.getPayload());
    }

    @Test
    public void testRetryAllowsOnlyFailedTask() throws Exception {
        LeaseBackend backend = createBackend();
        String pendingTaskId = submit(backend, PAY_TASK_TYPE, "pending");
        String runningTaskId = submit(backend, "running", PAY_TASK_TYPE, "running");
        String terminalTaskId = submit(backend, "terminal", PAY_TASK_TYPE, "terminal");

        assertRunningGrant(acquireFromQueue(backend, "running", PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS),
                runningTaskId, WORKER_A);
        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                "terminal", terminalTaskId, LeaseCompletion.cancelled("cancelled", null, null))));

        Assert.assertEquals(AdminResult.TERMINAL, backend.retry(RetryCommand.of(
                DEFAULT_QUEUE, pendingTaskId, 0L)));
        Assert.assertEquals(AdminResult.ACTIVE_LEASE_PRESENT, backend.retry(RetryCommand.of(
                "running", runningTaskId, 0L)));
        Assert.assertEquals(AdminResult.TERMINAL, backend.retry(RetryCommand.of(
                "terminal", terminalTaskId, 0L)));
    }

    @Test
    public void testUpdateChangesMutableTaskFields() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        Assert.assertEquals(AdminResult.APPLIED, backend.update(update(taskId,
                MAIL_TASK_TYPE, "changed", Integer.valueOf(9),
                Collections.singletonMap("traceId", "T-1"), true, null)));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(MAIL_TASK_TYPE, snapshot.getType());
        Assert.assertEquals("changed", snapshot.getPayload());
        Assert.assertEquals(9, snapshot.getPriority());
        Assert.assertEquals("T-1", snapshot.getAttributes().get("traceId"));
    }

    @Test
    public void testUpdateRejectsActiveLeaseAndKeepsTaskUnchanged() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS), taskId, WORKER_A);

        Assert.assertEquals(AdminResult.ACTIVE_LEASE_PRESENT, backend.update(update(taskId,
                MAIL_TASK_TYPE, "changed", Integer.valueOf(9), null, false, null)));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(PAY_TASK_TYPE, snapshot.getType());
        Assert.assertEquals("payload", snapshot.getPayload());
        Assert.assertEquals(0, snapshot.getPriority());
    }

    @Test
    public void testUpdateRejectsTerminalTaskAndKeepsTaskUnchanged() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, taskId, LeaseCompletion.cancelled("cancelled", null, null))));

        Assert.assertEquals(AdminResult.TERMINAL, backend.update(update(taskId,
                MAIL_TASK_TYPE, "changed", Integer.valueOf(9), null, false, null)));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(PAY_TASK_TYPE, snapshot.getType());
        Assert.assertEquals("payload", snapshot.getPayload());
        Assert.assertEquals(0, snapshot.getPriority());
    }

    @Test
    public void testUpdateWithoutAttributePatchKeepsAttributes() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.update(update(taskId,
                null, "changed", null, null, false, null)));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals("changed", snapshot.getPayload());
        Assert.assertEquals("T-1", snapshot.getAttributes().get("traceId"));
    }

    @Test
    public void testUpdateWithEmptyAttributePatchClearsAttributes() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.update(update(taskId,
                null, "changed", null, Collections.<String, String>emptyMap(), true, null)));

        Assert.assertTrue(snapshot(backend, DEFAULT_QUEUE, taskId).getAttributes().isEmpty());
    }

    @Test
    public void testUpdateAllowsTaskAfterLeaseExpiry() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        waitUntilAfter(grant.getSnapshot().getLeaseExpiresAt());
        Assert.assertEquals(AdminResult.APPLIED, backend.update(update(taskId,
                null, "changed", null, null, false, null)));
        Assert.assertEquals("changed", snapshot(backend, DEFAULT_QUEUE, taskId).getPayload());
    }

    @Test
    public void testUpdateExpiredRunningTaskKeepsLeaseOwnership() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);
        Instant leaseExpiresAt = grant.getSnapshot().getLeaseExpiresAt();

        waitUntilAfter(leaseExpiresAt);
        Assert.assertEquals(AdminResult.APPLIED, backend.update(update(taskId,
                null, "changed", Integer.valueOf(9), null, false, null)));

        TaskSnapshot updated = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.RUNNING, updated.getStatus());
        Assert.assertEquals(WORKER_A, updated.getWorkerId());
        Assert.assertEquals(leaseExpiresAt, updated.getLeaseExpiresAt());
        Assert.assertEquals("changed", updated.getPayload());
        Assert.assertEquals(9, updated.getPriority());

        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LONG_LEASE_MILLIS),
                taskId, WORKER_B);
    }

    @Test
    public void testUpdateAndRescheduleExpiredRunningTaskStartsNewCycle() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        waitUntilAfter(grant.getSnapshot().getLeaseExpiresAt());
        Assert.assertEquals(AdminResult.APPLIED, backend.updateAndReschedule(update(taskId,
                null, "changed", null, null, false, Long.valueOf(0L))));

        TaskSnapshot rescheduled = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, rescheduled.getStatus());
        Assert.assertNull(rescheduled.getWorkerId());
        Assert.assertNull(rescheduled.getLeaseExpiresAt());
        Assert.assertEquals("changed", rescheduled.getPayload());
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LONG_LEASE_MILLIS),
                taskId, WORKER_B);
    }

    @Test
    public void testUpdateAndRescheduleAppliesUpdateAndVisibilityTogether() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));

        Assert.assertEquals(AdminResult.APPLIED, backend.updateAndReschedule(update(taskId,
                MAIL_TASK_TYPE, "changed", Integer.valueOf(9),
                Collections.singletonMap("traceId", "T-2"), true, null)));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, snapshot.getStatus());
        Assert.assertEquals(MAIL_TASK_TYPE, snapshot.getType());
        Assert.assertEquals("changed", snapshot.getPayload());
        Assert.assertEquals(9, snapshot.getPriority());
        Assert.assertEquals("T-2", snapshot.getAttributes().get("traceId"));
        Assert.assertFalse(snapshot.getVisibleAt().isAfter(Instant.now()));
        assertRunningGrant(acquire(backend, MAIL_TASK_TYPE, WORKER_A, LEASE_MILLIS), taskId, WORKER_A);
    }

    @Test
    public void testUpdateAndRescheduleDelaysVisibility() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        Assert.assertEquals(AdminResult.APPLIED, backend.updateAndReschedule(update(taskId,
                null, "changed", null, null, false, Long.valueOf(LONG_LEASE_MILLIS))));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals("changed", snapshot.getPayload());
        Assert.assertTrue(snapshot.getVisibleAt().isAfter(Instant.now()));
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));
    }

    @Test
    public void testUpdateAndRescheduleRejectsActiveAndTerminalTasks() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String activeTaskId = submit(backend, PAY_TASK_TYPE, "active");
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS), activeTaskId, WORKER_A);
        String terminalTaskId = submit(backend, MAIL_TASK_TYPE, "terminal");
        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                DEFAULT_QUEUE, terminalTaskId, LeaseCompletion.cancelled("cancelled", null, null))));

        Assert.assertEquals(AdminResult.ACTIVE_LEASE_PRESENT, backend.updateAndReschedule(update(
                activeTaskId, null, "changed", null, null, false, null)));
        Assert.assertEquals(AdminResult.TERMINAL, backend.updateAndReschedule(update(
                terminalTaskId, null, "changed", null, null, false, null)));
    }

    private UpdateCommand update(String taskId, String taskType, String payload, Integer priority,
                                 java.util.Map<String, String> attributes, boolean attributesPresent,
                                 Long delayMillis) {
        return UpdateCommand.of(DEFAULT_QUEUE, taskId, taskType, payload, priority, attributes,
                attributesPresent, delayMillis);
    }
}
