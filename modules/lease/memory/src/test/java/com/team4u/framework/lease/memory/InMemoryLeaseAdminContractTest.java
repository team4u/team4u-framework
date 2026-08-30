package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseAdminContractTest;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.UpdateCommand;
import org.junit.Assert;
import org.junit.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Collections;

public class InMemoryLeaseAdminContractTest extends AbstractLeaseAdminContractTest {


    private static final long NOW_MILLIS = 10_000L;

    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }

    @Test
    public void testRescheduleUsesCurrentTimePlusDelay() {
        LeaseBackend backend = new InMemoryLeaseBackend(
                Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC));
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        Assert.assertEquals(AdminResult.APPLIED, backend.reschedule(RescheduleCommand.of(
                DEFAULT_QUEUE, taskId, 1L)));

        TaskSnapshot rescheduled = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(Instant.ofEpochMilli(NOW_MILLIS + 1L), rescheduled.getVisibleAt());
    }

    @Test
    public void testRescheduleZeroDelayUsesCurrentTime() {
        LeaseBackend backend = new InMemoryLeaseBackend(
                Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC));
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        Assert.assertEquals(AdminResult.APPLIED, backend.reschedule(RescheduleCommand.of(
                DEFAULT_QUEUE, taskId, 0L)));

        TaskSnapshot rescheduled = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(Instant.ofEpochMilli(NOW_MILLIS), rescheduled.getVisibleAt());
    }

    @Test
    public void testUpdateFailsFastWhenNewTypeDedupKeyIsOwnedByAnotherTask() {
        LeaseBackend backend = createBackend();
        String payTaskId = submitResult(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "pay",
                "dedup-1", 0L, Collections.<String, String>emptyMap()).getTaskId();
        String mailTaskId = submitResult(backend, DEFAULT_QUEUE, MAIL_TASK_TYPE, "mail",
                "dedup-1", 0L, Collections.<String, String>emptyMap()).getTaskId();

        try {
            backend.update(UpdateCommand.of(DEFAULT_QUEUE, mailTaskId, PAY_TASK_TYPE,
                    "changed", null, null, false, null));
            Assert.fail("expected dedup key conflict");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("dedup"));
        }

        assertDedupConflictUnchanged(backend, payTaskId, mailTaskId);
    }

    @Test
    public void testUpdateAndRescheduleFailsFastWhenNewTypeDedupKeyIsOwnedByAnotherTask() {
        LeaseBackend backend = createBackend();
        String payTaskId = submitResult(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "pay",
                "dedup-1", 0L, Collections.<String, String>emptyMap()).getTaskId();
        String mailTaskId = submitResult(backend, DEFAULT_QUEUE, MAIL_TASK_TYPE, "mail",
                "dedup-1", 0L, Collections.<String, String>emptyMap()).getTaskId();
        try {
            backend.updateAndReschedule(UpdateCommand.of(DEFAULT_QUEUE, mailTaskId, PAY_TASK_TYPE,
                    "changed", null, null, false, 1L));
            Assert.fail("expected dedup key conflict");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("dedup"));
        }

        assertDedupConflictUnchanged(backend, payTaskId, mailTaskId);
    }

    private void assertDedupConflictUnchanged(LeaseBackend backend, String payTaskId,
                                              String mailTaskId) {
        TaskSnapshot pay = snapshot(backend, DEFAULT_QUEUE, payTaskId);
        TaskSnapshot mail = snapshot(backend, DEFAULT_QUEUE, mailTaskId);
        Assert.assertEquals(PAY_TASK_TYPE, pay.getType());
        Assert.assertEquals("pay", pay.getPayload());
        Assert.assertEquals(MAIL_TASK_TYPE, mail.getType());
        Assert.assertEquals("mail", mail.getPayload());
        Assert.assertEquals(TaskStatus.PENDING, mail.getStatus());
        Assert.assertEquals(payTaskId, backend.getByDeduplicationKey(
                DEFAULT_QUEUE, PAY_TASK_TYPE, "dedup-1").get().getTaskId());
        Assert.assertEquals(mailTaskId, backend.getByDeduplicationKey(
                DEFAULT_QUEUE, MAIL_TASK_TYPE, "dedup-1").get().getTaskId());
    }
}
