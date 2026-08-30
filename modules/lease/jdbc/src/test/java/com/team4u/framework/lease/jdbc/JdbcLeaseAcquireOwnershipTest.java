package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import org.junit.Assert;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class JdbcLeaseAcquireOwnershipTest {

    private static final long BASE_TIME_MILLIS = 1_000L;
    private static final long SHORT_LEASE_MILLIS = 20L;
    private static final long LONG_LEASE_MILLIS = 500L;

    @Test
    public void testAcquireNeverReturnsHandleTakenOverBetweenCasAndOwnershipRead() throws Exception {
        final AtomicLong clock = new AtomicLong(BASE_TIME_MILLIS);
        final AtomicBoolean takeoverDone = new AtomicBoolean();
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        final JdbcLeaseBackend workerA = new JdbcLeaseBackend(
                observed.dataSource(), new MySqlLeaseDbDialect(), clock::get);
        JdbcLeaseBackend workerB = new JdbcLeaseBackend(
                observed.dataSource(), new MySqlLeaseDbDialect(), clock::get);
        String taskId = workerA.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.<String, String>emptyMap())).getTaskId();

        observed.addInterceptor(new ObservedDataSource.SqlInterceptor() {
            private final AtomicBoolean nested = new AtomicBoolean();

            @Override
            public void beforeExecute(String normalizedSql) throws SQLException {
                if (nested.get() || !normalizedSql.startsWith("SELECT")
                        || !takeoverDone.get()
                        || !normalizedSql.contains("LEASE_TOKEN = ? AND VERSION = ?")) {
                    return;
                }
                nested.set(true);
                try {
                    clock.set(BASE_TIME_MILLIS + SHORT_LEASE_MILLIS);
                    LeaseGrant grant = workerB.acquire(AcquireCommand.of(
                            JdbcLeaseHotPathTest.subscription("orders", "pay"),
                            "worker-b", LONG_LEASE_MILLIS));
                    LeaseGrantAssert.assertGrant(grant, taskId, "worker-b");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("nested acquire interrupted", e);
                } finally {
                    nested.set(false);
                }
            }
        });
        takeOverAfterFirstCas(observed, takeoverDone);

        LeaseGrant grantA = workerA.acquire(AcquireCommand.of(
                JdbcLeaseHotPathTest.subscription("orders", "pay"),
                "worker-a", SHORT_LEASE_MILLIS));

        Assert.assertNull("worker A must not receive worker B's fencing token", grantA);
        TaskSnapshot snapshot = workerB.get("orders", taskId).get();
        Assert.assertEquals("worker-b", snapshot.getWorkerId());
        Assert.assertEquals(2, snapshot.getAttemptCount());
    }

    @Test
    public void testShortHeartbeatIsAppliedWithoutShorteningLease() throws Exception {
        AtomicLong clock = new AtomicLong(BASE_TIME_MILLIS);
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                JdbcLeaseBackendTestSupport.newDataSource(), new MySqlLeaseDbDialect(),
                clock::get);
        String taskId = backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.<String, String>emptyMap())).getTaskId();
        LeaseGrant grant = backend.acquire(AcquireCommand.of(
                JdbcLeaseHotPathTest.subscription("orders", "pay"), "worker-a", 5_000L));
        TaskSnapshot before = backend.get("orders", taskId).get();
        clock.set(BASE_TIME_MILLIS + 100L);

        RuntimeResult result = backend.heartbeat(grant.getHandle(), 100L);
        TaskSnapshot after = backend.get("orders", taskId).get();

        Assert.assertEquals(RuntimeResult.APPLIED, result);
        Assert.assertEquals(before.getLeaseExpiresAt(), after.getLeaseExpiresAt());
    }

    @Test
    public void testExtremePageDoesNotOverflowToNegativeOffset() {
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());
        backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.<String, String>emptyMap()));

        try {
            backend.list("orders", TaskQuery.builder()
                    .page(Integer.MAX_VALUE)
                    .pageSize(Integer.MAX_VALUE)
                    .build());
            Assert.fail("expected extreme pagination offset to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("offset is too large"));
        }
        Assert.assertEquals(1, observed.executionCount());
    }

    private static void takeOverAfterFirstCas(ObservedDataSource observed,
                                              final AtomicBoolean takeoverDone) {
        observed.addInterceptor(new ObservedDataSource.SqlInterceptor() {
            private int updates;

            @Override
            public void beforeExecute(String normalizedSql) {
                if (normalizedSql.startsWith("UPDATE")) {
                    updates++;
                    if (updates == 1) {
                        takeoverDone.set(true);
                    }
                }
            }
        });
    }
}
