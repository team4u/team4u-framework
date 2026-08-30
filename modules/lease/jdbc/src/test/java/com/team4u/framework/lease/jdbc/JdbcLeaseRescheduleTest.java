package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.SubmitCommand;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

public class JdbcLeaseRescheduleTest {

    @Test
    public void testZeroDelayMovesFutureTaskToOperationNowAndMakesItAcquirable() throws InterruptedException {
        AtomicLong clock = new AtomicLong(1_000L);
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                JdbcLeaseBackendTestSupport.newDataSource(), new MySqlLeaseDbDialect(),
                clock::get);
        String taskId = backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                100L, 0, Collections.<String, String>emptyMap())).getTaskId();
        TaskSnapshot delayed = backend.get("orders", taskId).get();
        clock.set(1_050L);

        Assert.assertEquals(AdminResult.APPLIED, backend.reschedule(
                RescheduleCommand.of("orders", taskId, 0L)));

        TaskSnapshot rescheduled = backend.get("orders", taskId).get();
        Assert.assertEquals(Instant.ofEpochMilli(1_050L), rescheduled.getVisibleAt());
        Assert.assertTrue(rescheduled.getVisibleAt().isBefore(delayed.getVisibleAt()));
        LeaseGrantAssert.assertGrant(backend.acquire(AcquireCommand.of(
                JdbcLeaseHotPathTest.subscription("orders", "pay"),
                "worker-a", 500L)), taskId, "worker-a");
    }
}
