package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import org.junit.Assert;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

public class LeaseQuickstartTest {

    @Test
    public void memoryBackendCompletesLeaseLifecycleWithTokenFencing() throws Exception {
        AtomicLong currentTime = new AtomicLong(1_000L);
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend(new MutableClock(currentTime));

        SubmitResult submission = backend.submit(SubmitCommand.of(
                "orders", "email.send", "{\"orderId\":\"O-1001\"}", "O-1001",
                0L, 10, Collections.singletonMap("traceId", "trace-1")));
        Assert.assertTrue(submission.isCreated());
        Assert.assertEquals(TaskStatus.PENDING, submission.getSnapshot().getStatus());
        Assert.assertEquals(0, submission.getSnapshot().getAttemptCount());

        LeaseGrant grant = backend.acquire(AcquireCommand.of(
                TaskSubscription.of("orders", Collections.singleton("email.send")),
                "worker-a", 500L));
        Assert.assertNotNull(grant);
        Assert.assertEquals(submission.getTaskId(), grant.getHandle().getTaskId());
        Assert.assertEquals("worker-a", grant.getSnapshot().getWorkerId());
        Assert.assertEquals(TaskStatus.RUNNING, grant.getSnapshot().getStatus());
        Assert.assertEquals(1, grant.getSnapshot().getAttemptCount());
        Assert.assertNotNull(grant.getHandle().getLeaseToken());
        Assert.assertEquals(Instant.ofEpochMilli(1_500L), grant.getSnapshot().getLeaseExpiresAt());

        currentTime.set(1_200L);
        Assert.assertEquals(RuntimeResult.APPLIED,
                backend.heartbeat(grant.getHandle(), 700L));
        Assert.assertEquals(Instant.ofEpochMilli(1_900L),
                backend.get("orders", submission.getTaskId()).get().getLeaseExpiresAt());

        LeaseHandle expiredHandle = LeaseHandle.of(
                grant.getHandle().getTaskId(), grant.getHandle().getWorkerId(), "forged-token");
        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.heartbeat(expiredHandle, 100L));

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.succeeded("{\"sent\":true}",
                        Collections.singletonMap("traceId", "trace-1"))));
        TaskSnapshot completed = backend.get("orders", submission.getTaskId()).get();
        Assert.assertEquals(TaskStatus.SUCCEEDED, completed.getStatus());
        Assert.assertEquals("{\"sent\":true}", completed.getPayload());
        Assert.assertEquals("trace-1", completed.getAttributes().get("traceId"));
        Assert.assertNull(completed.getWorkerId());
        Assert.assertNull(completed.getLeaseExpiresAt());

        Assert.assertEquals(RuntimeResult.TERMINAL,
                backend.close(grant.getHandle(), LeaseCompletion.failed("late failure", null, null)));
        Assert.assertEquals(AdminResult.TERMINAL, backend.complete(AdminCompletionCommand.of(
                "orders", submission.getTaskId(),
                LeaseCompletion.cancelled("late cancel", null, null))));

        TaskSnapshot duplicate = backend.submit(SubmitCommand.of(
                "orders", "email.send", "{\"orderId\":\"O-1002\"}", "O-1001",
                0L, 0, Collections.<String, String>emptyMap())).getSnapshot();
        Assert.assertEquals(submission.getTaskId(), duplicate.getTaskId());
        Assert.assertEquals(TaskStatus.SUCCEEDED, duplicate.getStatus());

        Assert.assertNull(backend.acquire(AcquireCommand.of(
                TaskSubscription.of("orders", Collections.singleton("email.send")),
                "worker-b", 500L)));
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        private MutableClock(AtomicLong millis) {
            this.millis = millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }
}
