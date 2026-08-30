package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import com.team4u.framework.lease.spi.UpdateCommand;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import com.team4u.framework.base.jdbc.JdbcUtil;
import org.junit.Assert;
import org.junit.Test;

import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
public class JdbcLeaseHotPathTest {

    private static final long BASE_TIME_MILLIS = 1_000L;
    private static final long LEASE_MILLIS = 20L;

    @Test
    public void testSubmitWithoutDeduplicationKeyUsesSingleInsert() {
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());

        SubmitResult result = backend.submit(SubmitCommand.of("orders", "pay", "payload",
                null, 0L, 0, Collections.singletonMap("traceId", "T-1")));

        Assert.assertTrue(result.isCreated());
        Assert.assertEquals(1, observed.executionCount());
        Assert.assertTrue(observed.executedSql().get(0).startsWith("INSERT INTO"));
    }

    @Test
    public void testDuplicateDeduplicationKeyUsesInsertAndSingleLookup() {
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());
        SubmitCommand first = SubmitCommand.of("orders", "pay", "v1", "dedup-1",
                0L, 0, Collections.<String, String>emptyMap());

        Assert.assertTrue(backend.submit(first).isCreated());
        observed.resetCount();
        SubmitResult duplicate = backend.submit(SubmitCommand.of("orders", "pay", "v2",
                "dedup-1", 0L, 0, Collections.<String, String>emptyMap()));

        Assert.assertFalse(duplicate.isCreated());
        Assert.assertEquals("v1", duplicate.getSnapshot().getPayload());
        Assert.assertEquals(2, observed.executionCount());
        Assert.assertEquals(1, countSql(observed, "SELECT"));
        Assert.assertTrue(observed.executedSql().get(1).contains("DEDUPLICATION_KEY = ?"));
    }

    @Test
    public void testNullDeduplicationKeyDoesNotUseConflictLookup() {
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());

        backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.<String, String>emptyMap()));
        backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.<String, String>emptyMap()));

        Assert.assertEquals(2, observed.executionCount());
        Assert.assertEquals(2, countSql(observed, "INSERT INTO"));
        Assert.assertEquals(0, countSql(observed, "SELECT"));
    }

    @Test
    public void testAcquireUsesTypedUnionCandidateQueryAndAtomicUpdate() throws Exception {
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());
        String taskId = backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.<String, String>emptyMap())).getTaskId();
        observed.resetCount();

        LeaseGrantAssert.assertGrant(backend.acquire(AcquireCommand.of(
                subscription("orders", "mail", "pay"), "worker-a", 500L)), taskId, "worker-a");

        Assert.assertEquals(3, observed.executionCount());
        String candidateSql = observed.executedSql().get(0);
        Assert.assertTrue(candidateSql.contains("FROM ("));
        Assert.assertTrue(candidateSql.contains("QUEUE_NAME = ?"));
        Assert.assertEquals(2, countOccurrences(candidateSql, "TASK_TYPE IN (?, ?)"));
        Assert.assertEquals(2, countOccurrences(candidateSql, "STATUS = ?"));
        Assert.assertTrue(candidateSql.contains("VISIBLE_AT <= ?"));
        Assert.assertTrue(candidateSql.contains("LEASE_EXPIRES_AT <= ?"));
        Assert.assertTrue(candidateSql.contains("UNION ALL"));
        String updateSql = observed.executedSql().get(1);
        Assert.assertTrue(updateSql.startsWith("UPDATE"));
        Assert.assertTrue(updateSql.contains("ATTEMPT_COUNT = ATTEMPT_COUNT + 1"));
        Assert.assertTrue(updateSql.contains("VERSION = ?"));
        Assert.assertTrue(updateSql.contains("STATUS = ?"));
    }

    @Test
    public void testCandidateQueryParametersFollowTypedUnionBranches() throws Exception {
        AtomicLong clock = new AtomicLong(BASE_TIME_MILLIS);
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                observed.dataSource(), new MySqlLeaseDbDialect(), clock::get);

        String pendingId = backend.submit(SubmitCommand.of("orders", "mail", "pending", null,
                0L, 0, Collections.<String, String>emptyMap())).getTaskId();
        backend.submit(SubmitCommand.of("orders", "audit", "wrong-type", null,
                0L, 0, Collections.<String, String>emptyMap()));
        String runningId = backend.submit(SubmitCommand.of("orders", "pay", "running", null,
                0L, 0, Collections.<String, String>emptyMap())).getTaskId();
        LeaseGrantAssert.assertGrant(backend.acquire(AcquireCommand.of(
                subscription("orders", "pay"), "worker-a", LEASE_MILLIS)), runningId, "worker-a");
        clock.set(BASE_TIME_MILLIS + LEASE_MILLIS);

        JdbcLeaseTaskDao dao = new JdbcLeaseTaskDao(
                observed.dataSource(), new MySqlLeaseDbDialect());
        Set<String> candidateIds = new LinkedHashSet<String>();
        for (LeaseTaskEntity candidate : dao.findAcquirableTasks(
                subscription("orders", "mail", "pay"), clock.get(), 10)) {
            candidateIds.add(candidate.getTaskId());
        }

        Assert.assertEquals(new LinkedHashSet<String>(Arrays.asList(pendingId, runningId)),
                candidateIds);
        List<List<Object>> executions = observed.executedParameters();
        List<Object> unionParameters = executions.get(executions.size() - 1);
        Assert.assertEquals(Arrays.<Object>asList(
                "orders", "mail", "pay", "PENDING",
                        Long.valueOf(BASE_TIME_MILLIS + LEASE_MILLIS),
                "orders", "mail", "pay", "RUNNING",
                        Long.valueOf(BASE_TIME_MILLIS + LEASE_MILLIS),
                Integer.valueOf(10)),
                unionParameters);
    }


    @Test
    public void testUpdateAndRescheduleUsesSingleAtomicUpdate() {
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());
        String taskId = backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.singletonMap("traceId", "T-1"))).getTaskId();
        observed.resetCount();

        backend.updateAndReschedule(UpdateCommand.of("orders", taskId, "mail", "changed",
                9, Collections.<String, String>emptyMap(), true, 0L));

        Assert.assertEquals(1, countSql(observed, "UPDATE"));
        Assert.assertEquals(TaskStatus.PENDING, backend.get("orders", taskId).get().getStatus());
        Assert.assertEquals("mail", backend.get("orders", taskId).get().getType());
        Assert.assertTrue(backend.get("orders", taskId).get().getAttributes().isEmpty());
    }

    @Test
    public void testCompleteUsesSingleConditionalUpdateAndPatchesTask() {
        AtomicLong clock = new AtomicLong(BASE_TIME_MILLIS);
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                observed.dataSource(), new MySqlLeaseDbDialect(), clock::get);
        String taskId = backend.submit(SubmitCommand.of("orders", "pay", "payload-v1",
                null, 0L, 0, Collections.singletonMap("traceId", "T-1"))).getTaskId();
        observed.resetCount();

        LeaseCompletion completion = LeaseCompletion.failed(
                "boom", "payload-v2", Collections.singletonMap("traceId", "T-2"));
        Assert.assertEquals(AdminResult.APPLIED, backend.complete(
                AdminCompletionCommand.of("orders", taskId, completion)));

        Assert.assertEquals(1, observed.executionCount());
        String updateSql = observed.executedSql().get(0);
        Assert.assertTrue(updateSql.startsWith("UPDATE"));
        Assert.assertTrue(updateSql.contains("WORKER_ID = ?"));
        Assert.assertTrue(updateSql.contains("LEASE_TOKEN = ?"));
        Assert.assertTrue(updateSql.contains("LEASE_EXPIRES_AT = ?"));
        Assert.assertTrue(updateSql.contains("VERSION = VERSION + 1"));
        Assert.assertTrue(updateSql.contains("STATUS NOT IN (?, ?, ?)"));
        Assert.assertTrue(updateSql.contains(
                "NOT (STATUS = ? AND LEASE_EXPIRES_AT > ?)"));
        Assert.assertEquals(Arrays.<Object>asList(
                TaskStatus.FAILED.name(), "boom", null, null, null,
                Long.valueOf(BASE_TIME_MILLIS), "payload-v2",
                "{\"traceId\":\"T-2\"}", taskId, "orders",
                TaskStatus.SUCCEEDED.name(), TaskStatus.FAILED.name(),
                TaskStatus.CANCELLED.name(), TaskStatus.RUNNING.name(),
                Long.valueOf(BASE_TIME_MILLIS)),
                observed.executedParameters().get(0));
    }

    @Test
    public void testCompleteAllowsRunningTaskAtExactLeaseExpiry() throws InterruptedException {
        AtomicLong clock = new AtomicLong(BASE_TIME_MILLIS);
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                JdbcLeaseBackendTestSupport.newDataSource(), new MySqlLeaseDbDialect(),
                clock::get);
        String taskId = backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.<String, String>emptyMap())).getTaskId();
        backend.acquire(AcquireCommand.of(subscription("orders", "pay"),
                "worker-a", LEASE_MILLIS));
        clock.set(BASE_TIME_MILLIS + LEASE_MILLIS);

        Assert.assertEquals(AdminResult.APPLIED, backend.complete(AdminCompletionCommand.of(
                "orders", taskId, LeaseCompletion.cancelled("expired", null, null))));

        TaskSnapshot completed = backend.get("orders", taskId).get();
        Assert.assertEquals(TaskStatus.CANCELLED, completed.getStatus());
        Assert.assertNull(completed.getWorkerId());
        Assert.assertNull(completed.getLeaseExpiresAt());
    }

    @Test
    public void testUpdateTaskTypeDuplicateKeyFailsWithoutPartialUpdate() {
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());
        backend.submit(SubmitCommand.of("orders", "mail", "existing", "dedup-1",
                0L, 0, Collections.<String, String>emptyMap()));
        String taskId = backend.submit(SubmitCommand.of("orders", "pay", "target",
                "dedup-1", 0L, 0, Collections.<String, String>emptyMap())).getTaskId();
        observed.resetCount();

        try {
            backend.update(UpdateCommand.of("orders", taskId, "mail", "changed",
                    9, Collections.<String, String>emptyMap(), true, null));
            Assert.fail("expected deduplication-key conflict");
        } catch (IllegalStateException e) {
            Assert.assertEquals(1, observed.executionCount());
            Assert.assertEquals(1, countSql(observed, "UPDATE"));
        }

        TaskSnapshot unchanged = backend.get("orders", taskId).get();
        Assert.assertEquals(TaskStatus.PENDING, unchanged.getStatus());
        Assert.assertEquals("pay", unchanged.getType());
        Assert.assertEquals("target", unchanged.getPayload());
        Assert.assertEquals(0, unchanged.getPriority());
        Assert.assertTrue(unchanged.getAttributes().isEmpty());
    }
    @Test
    public void testRunningTaskAtExactLeaseExpiryIsCandidateAndFencedHandleLoses() throws InterruptedException {
        AtomicLong clock = new AtomicLong(BASE_TIME_MILLIS);
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                JdbcLeaseBackendTestSupport.newDataSource(),
                new MySqlLeaseDbDialect(), clock::get);
        String taskId = backend.submit(SubmitCommand.of("orders", "pay", "payload", null,
                0L, 0, Collections.<String, String>emptyMap())).getTaskId();
        LeaseHandle first = backend.acquire(AcquireCommand.of(
                subscription("orders", "pay"), "worker-a", LEASE_MILLIS)).getHandle();
        clock.set(BASE_TIME_MILLIS + LEASE_MILLIS);

        LeaseGrantAssert.assertGrant(backend.acquire(AcquireCommand.of(
                subscription("orders", "pay"), "worker-b", 500L)), taskId, "worker-b");
        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.close(first,
                LeaseCompletion.succeeded(null, null)));
        Assert.assertEquals(2, backend.get("orders", taskId).get().getAttemptCount());
    }

    @Test
    public void testAcquireReturnsNullAfterCandidateCasRaceWithoutHotLoop() throws Exception {
        AtomicLong clock = new AtomicLong(BASE_TIME_MILLIS);
        ObservedDataSource observed = ObservedDataSource.wrap(
                JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                observed.dataSource(), new MySqlLeaseDbDialect(), clock::get);
        backend.submit(SubmitCommand.of("orders", "pay", "first", null,
                0L, 0, Collections.<String, String>emptyMap()));
        backend.submit(SubmitCommand.of("orders", "pay", "second", null,
                0L, 0, Collections.<String, String>emptyMap()));

        // Force stale candidate versions so both conditional updates lose the CAS.
        observed.addInterceptor(new ObservedDataSource.SqlInterceptor() {
            private int updates;

            @Override
            public void beforeExecute(String normalizedSql) throws SQLException {
                if (!normalizedSql.startsWith("UPDATE")) {
                    return;
                }
                updates++;
                if (updates <= 2) {
                    JdbcUtil.execute(observed.dataSource(),
                            "UPDATE lease_task SET version = version + 1, updated_at = ?",
                            Long.valueOf(clock.get()));
                }
            }
        });

        Assert.assertNull(backend.acquire(AcquireCommand.of(
                subscription("orders", "pay"), "worker-b", 500L)));
        Assert.assertEquals(1, countOccurrences(joinSql(observed), "FROM ("));
        Assert.assertEquals(4, countSql(observed, "UPDATE"));
    }

    static TaskSubscription subscription(String queue, String... types) {
        return TaskSubscription.of(queue, new LinkedHashSet<String>(Arrays.asList(types)));
    }

    private static int countSql(ObservedDataSource observed, String prefix) {
        int count = 0;
        for (String sql : observed.executedSql()) {
            if (sql.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static String joinSql(ObservedDataSource observed) {
        StringBuilder sql = new StringBuilder();
        for (String statement : observed.executedSql()) {
            sql.append(statement).append('\n');
        }
        return sql.toString();
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
