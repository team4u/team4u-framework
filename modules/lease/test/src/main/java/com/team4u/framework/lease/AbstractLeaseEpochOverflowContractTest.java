package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.RetryCommand;
import com.team4u.framework.lease.spi.UpdateCommand;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public abstract class AbstractLeaseEpochOverflowContractTest extends AbstractLeaseContractSupport {

    private static final long HUGE_MILLIS = Long.MAX_VALUE;

    @Test
    public void testHugeSubmitDelayThrowsWithoutCreatingTask() {
        LeaseBackend backend = createBackend();

        try {
            submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, HUGE_MILLIS,
                    Collections.<String, String>emptyMap());
            Assert.fail("expected huge submit delay to be rejected");
        } catch (IllegalArgumentException expected) {
            assertOverflowMessage(expected);
        }
        Assert.assertEquals(0, backend.list(DEFAULT_QUEUE, query()).getTasks().size());
    }

    @Test
    public void testHugeAcquireLeaseThrowsWithoutCreatingLease() throws InterruptedException {
        LeaseBackend backend = createBackend();

        try {
            acquire(backend, PAY_TASK_TYPE, WORKER_A, HUGE_MILLIS);
            Assert.fail("expected huge acquire lease to be rejected");
        } catch (IllegalArgumentException expected) {
            assertOverflowMessage(expected);
        }
    }

    @Test
    public void testHugeHeartbeatThrowsWithoutChangingLease() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A,
                LEASE_MILLIS), taskId, WORKER_A);
        TaskSnapshot before = snapshot(backend, DEFAULT_QUEUE, taskId);

        try {
            backend.heartbeat(grant.getHandle(), HUGE_MILLIS);
            Assert.fail("expected huge heartbeat to be rejected");
        } catch (IllegalArgumentException expected) {
            assertOverflowMessage(expected);
        }

        TaskSnapshot after = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(before.getLeaseExpiresAt(), after.getLeaseExpiresAt());
        Assert.assertEquals(before.getStatus(), after.getStatus());
        Assert.assertEquals(before.getAttemptCount(), after.getAttemptCount());
    }

    @Test
    public void testHugeRetryReleaseThrowsWithoutChangingTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A,
                LEASE_MILLIS), taskId, WORKER_A);
        TaskSnapshot before = snapshot(backend, DEFAULT_QUEUE, taskId);

        try {
            backend.release(grant.getHandle(), LeaseRetry.of(HUGE_MILLIS, null, null, null));
            Assert.fail("expected huge retry release to be rejected");
        } catch (IllegalArgumentException expected) {
            assertOverflowMessage(expected);
        }

        TaskSnapshot after = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(before.getStatus(), after.getStatus());
        Assert.assertEquals(before.getVisibleAt(), after.getVisibleAt());
        Assert.assertEquals(before.getLeaseExpiresAt(), after.getLeaseExpiresAt());
    }

    @Test
    public void testHugeRescheduleThrowsWithoutChangingTask() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        TaskSnapshot before = snapshot(backend, DEFAULT_QUEUE, taskId);

        try {
            backend.reschedule(RescheduleCommand.of(DEFAULT_QUEUE, taskId, HUGE_MILLIS));
            Assert.fail("expected huge reschedule to be rejected");
        } catch (IllegalArgumentException expected) {
            assertOverflowMessage(expected);
        }

        TaskSnapshot after = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(before.getStatus(), after.getStatus());
        Assert.assertEquals(before.getVisibleAt(), after.getVisibleAt());
    }

    @Test
    public void testHugeAdminRetryThrowsWithoutChangingTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A,
                LEASE_MILLIS), taskId, WORKER_A);
        backend.close(grant.getHandle(), failed());
        TaskSnapshot before = snapshot(backend, DEFAULT_QUEUE, taskId);

        try {
            backend.retry(RetryCommand.of(DEFAULT_QUEUE, taskId, HUGE_MILLIS));
            Assert.fail("expected huge admin retry to be rejected");
        } catch (IllegalArgumentException expected) {
            assertOverflowMessage(expected);
        }

        TaskSnapshot after = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(before.getStatus(), after.getStatus());
        Assert.assertEquals(before.getVisibleAt(), after.getVisibleAt());
    }

    @Test
    public void testHugeUpdateAndRescheduleThrowsWithoutChangingTask() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        TaskSnapshot before = snapshot(backend, DEFAULT_QUEUE, taskId);

        try {
            backend.updateAndReschedule(UpdateCommand.of(DEFAULT_QUEUE, taskId, null,
                    "changed", null, null, false, Long.valueOf(HUGE_MILLIS)));
            Assert.fail("expected huge updateAndReschedule to be rejected");
        } catch (IllegalArgumentException expected) {
            assertOverflowMessage(expected);
        }

        TaskSnapshot after = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(before.getStatus(), after.getStatus());
        Assert.assertEquals(before.getVisibleAt(), after.getVisibleAt());
        Assert.assertEquals(before.getPayload(), after.getPayload());
    }

    private com.team4u.framework.lease.api.TaskQuery query() {
        return com.team4u.framework.lease.api.TaskQuery.builder().build();
    }

    private com.team4u.framework.lease.spi.LeaseCompletion failed() {
        return com.team4u.framework.lease.spi.LeaseCompletion.failed("failed", null, null);
    }

    private static void assertOverflowMessage(IllegalArgumentException ex) {
        Assert.assertNotNull(ex.getMessage());
        Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("overflows Long.MAX_VALUE"));
    }
}
