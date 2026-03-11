package com.team4u.framework.lease.jdbc;

import cn.hutool.db.Db;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.jdbc.codec.LeaseJsonCodec;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import com.team4u.framework.lease.model.*;
import org.junit.Assert;
import org.junit.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * JDBC 租赁后端热点路径优化测试
 * <p>
 * 该测试类专注于验证 `JdbcLeaseBackend` 在高并发及复杂调度场景下的行为正确性与性能优化手段。
 * 覆盖的主要场景包括：
 * 1. <b>幂等发布优化：</b> 验证 `publishIfAbsent` 在有无业务键情况下的数据库交互次数。
 * 2. <b>抢占逻辑优化：</b> 验证通过乐观锁（version）避免并发抢占冲突。
 * 3. <b>索引与排序验证：</b> 验证方言中产生的 SQL 是否能跨不同任务状态（READY, RUNNING）正确排序。
 */
public class JdbcLeaseHotPathOptimizationTest {

    /**
     * 验证在不提供业务键时，publishIfAbsent 应退化为普通插入，且只产生单条 SQL。
     */
    @Test
    public void testPublishIfAbsentWithoutBusinessKeyUsesSingleStatement() {
        ObservedDataSource observed = ObservedDataSource.wrap(JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());

        LeasePublishResult result = backend.publishIfAbsent(LeasePublishRequest.builder()
                .queue("retry-q")
                .taskType("recover-payment")
                .payload("{\"attempt\":1}")
                .attribute("traceId", "trace-1")
                .build());

        Assert.assertTrue(result.isCreated());
        Assert.assertEquals(1, observed.executionCount());
        Assert.assertNotNull(result.getRecord());
        Assert.assertEquals("{\"attempt\":1}", result.getRecord().getPayload());
        Assert.assertEquals("trace-1", result.getRecord().getAttributes().get("traceId"));
    }

    /**
     * 验证在提供业务键且任务不存在时，publishIfAbsent 能一次性完成插入。
     */
    @Test
    public void testPublishIfAbsentWithBusinessKeyCreateUsesSingleStatement() {
        ObservedDataSource observed = ObservedDataSource.wrap(JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());

        LeasePublishResult result = backend.publishIfAbsent(LeasePublishRequest.builder()
                .queue("retry-q")
                .taskType("recover-payment")
                .payload("{\"attempt\":1}")
                .businessKey("recover-payment|order-1001")
                .build());

        Assert.assertTrue(result.isCreated());
        Assert.assertEquals(1, observed.executionCount());
        Assert.assertEquals("{\"attempt\":1}", result.getRecord().getPayload());
    }

    /**
     * 验证在业务键冲突时，publishIfAbsent 能正确处理异常并通过单次查询找回已有任务。
     */
    @Test
    public void testPublishIfAbsentDuplicateUsesInsertAndSingleLookup() {
        ObservedDataSource observed = ObservedDataSource.wrap(JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());
        LeasePublishRequest request = LeasePublishRequest.builder()
                .queue("retry-q")
                .taskType("recover-payment")
                .payload("{\"attempt\":1}")
                .businessKey("recover-payment|order-1001")
                .build();

        LeasePublishResult created = backend.publishIfAbsent(request);
        observed.resetCount();
        LeasePublishResult existing = backend.publishIfAbsent(request);

        Assert.assertTrue(created.isCreated());
        Assert.assertFalse(existing.isCreated());
        Assert.assertEquals(2, observed.executionCount());
        Assert.assertEquals(created.getTaskId(), existing.getTaskId());
    }

    /**
     * 验证抢占热点路径：一次候选查询 + 一次乐观锁更新。
     */
    @Test
    public void testAcquireSuccessUsesCandidateQueryAndSingleUpdateOnly() throws Exception {
        ObservedDataSource observed = ObservedDataSource.wrap(JdbcLeaseBackendTestSupport.newDataSource());
        JdbcLeaseBackend backend = new JdbcLeaseBackend(observed.dataSource());
        backend.publish(LeasePublishRequest.builder()
                .queue("pay")
                .taskType("charge")
                .payload("payload")
                .build());
        observed.resetCount();

        LeaseGrant grant = backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-a")
                .leaseMillis(200L)
                .waitTimeoutMillis(100L)
                .subscription(LeaseSubscription.builder().queue("pay").build())
                .build());

        Assert.assertNotNull(grant);
        Assert.assertEquals(2, observed.executionCount());
    }

    /**
     * 模拟高并发发布场景，确保 publishIfAbsent 能够保证全局仅创建一个任务。
     */
    @Test
    public void testConcurrentPublishIfAbsentCreatesSingleTask() throws Exception {
        final JdbcLeaseBackend backend = new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
        final CountDownLatch ready = new CountDownLatch(6);
        final CountDownLatch start = new CountDownLatch(1);
        final List<LeasePublishResult> results = new ArrayList<LeasePublishResult>();
        final List<Throwable> failures = new ArrayList<Throwable>();
        List<Thread> threads = new ArrayList<Thread>();

        for (int i = 0; i < 6; i++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                    try {
                        start.await(1, TimeUnit.SECONDS);
                        LeasePublishResult result = backend.publishIfAbsent(LeasePublishRequest.builder()
                                .queue("retry-q")
                                .taskType("recover-payment")
                                .payload("{\"attempt\":1}")
                                .businessKey("recover-payment|order-1001")
                                .build());
                        synchronized (results) {
                            results.add(result);
                        }
                    } catch (Throwable t) {
                        synchronized (failures) {
                            failures.add(t);
                        }
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }

        Assert.assertTrue(ready.await(1, TimeUnit.SECONDS));
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        Assert.assertTrue(failures.isEmpty());
        Assert.assertEquals(6, results.size());

        int createdCount = 0;
        String taskId = null;
        for (LeasePublishResult result : results) {
            if (result.isCreated()) {
                createdCount++;
            }
            if (taskId == null) {
                taskId = result.getTaskId();
            }
            Assert.assertEquals(taskId, result.getTaskId());
        }
        Assert.assertEquals(1, createdCount);
    }

    @Test
    public void testAdminMutationClassificationUsesCapturedNowAtLeaseBoundary() throws Exception {
        DataSource dataSource = JdbcLeaseBackendTestSupport.newDataSource();
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                dataSource,
                new MySqlLeaseDbDialect(),
                new FixedClock(0L));
        String taskId = backend.publish(LeasePublishRequest.builder()
                .queue("pay")
                .taskType("charge")
                .payload("payload")
                .build());
        LeaseGrant grant = backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-a")
                .leaseMillis(100L)
                .waitTimeoutMillis(100L)
                .subscription(LeaseSubscription.builder().queue("pay").build())
                .build());
        Assert.assertNotNull(grant);

        JdbcLeaseBackend boundaryBackend = new JdbcLeaseBackend(
                dataSource,
                new MySqlLeaseDbDialect(),
                new SequenceClock(100L, 101L));

        Assert.assertEquals(LeaseAdminResult.ACTIVE_LEASE_PRESENT, boundaryBackend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .payload("changed")
                .build()));
    }

    @Test
    public void testAdminMutationAppliesAfterLeaseExpiry() throws Exception {
        DataSource dataSource = JdbcLeaseBackendTestSupport.newDataSource();
        JdbcLeaseBackend backend = new JdbcLeaseBackend(
                dataSource,
                new MySqlLeaseDbDialect(),
                new FixedClock(0L));
        String taskId = backend.publish(LeasePublishRequest.builder()
                .queue("pay")
                .taskType("charge")
                .payload("payload")
                .build());
        LeaseGrant grant = backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-a")
                .leaseMillis(100L)
                .waitTimeoutMillis(100L)
                .subscription(LeaseSubscription.builder().queue("pay").build())
                .build());
        Assert.assertNotNull(grant);

        JdbcLeaseBackend expiredBackend = new JdbcLeaseBackend(
                dataSource,
                new MySqlLeaseDbDialect(),
                new FixedClock(101L));

        Assert.assertEquals(LeaseAdminResult.APPLIED, expiredBackend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .payload("changed")
                .build()));
        Assert.assertEquals("changed", expiredBackend.get(taskId).get().getPayload());
    }

    /**
     * 验证抢占重试机制：当任务在“被查出为候选者”到“真正执行抢占”之间被第三方修改（导致 version 变更）时，
     * 抢占应失败，且 `acquire` 循环应能继续尝试后续候选者或下一次轮询，最终获取到最新状态。
     */
    @Test
    public void testAcquireRetriesWhenTaskChangesBetweenReadAndClaim() throws Exception {
        DataSource dataSource = JdbcLeaseBackendTestSupport.newDataSource();
        JdbcLeaseBackend backend = new JdbcLeaseBackend(dataSource);
        JdbcLeaseTaskDao dao = new JdbcLeaseTaskDao(Db.use(dataSource), new MySqlLeaseDbDialect(), new LeaseJsonCodec());
        String taskId = backend.publish(LeasePublishRequest.builder()
                .queue("pay")
                .taskType("charge")
                .payload("payload-v1")
                .build());
        long now = System.currentTimeMillis();
        LeaseTaskEntity staleCandidate = dao.findAcquirableTasks(
                Collections.singleton(LeaseSubscription.builder().queue("pay").build()),
                now,
                10).get(0);

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .payload("payload-v2")
                .build()));
        int updated = dao.tryAcquire(
                taskId,
                "worker-a",
                "lease-token-stale",
                now + 200L,
                now,
                staleCandidate.getVersion());

        Assert.assertEquals(0, updated);
        LeaseGrant grant = backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-a")
                .leaseMillis(200L)
                .waitTimeoutMillis(500L)
                .subscription(LeaseSubscription.builder().queue("pay").build())
                .build());
        Assert.assertNotNull(grant);
        Assert.assertEquals("payload-v2", grant.getPayload());
    }

    /**
     * 验证跨状态排序逻辑：确保优先级高的 READY 任务能够比已过期的 RUNNING 任务优先被抢占。
     * 这依赖于 SQL 中的 UNION ALL 与外部 ORDER BY 的正确配合。
     */
    @Test
    public void testAcquireOrderingStillWorksAcrossReadyAndExpiredCandidates() throws Exception {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
        String expiredTaskId = backend.publish(LeasePublishRequest.builder()
                .queue("pay")
                .taskType("charge")
                .payload("expired")
                .priority(5)
                .build());
        LeaseGrant first = backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-a")
                .leaseMillis(30L)
                .waitTimeoutMillis(100L)
                .subscription(LeaseSubscription.builder().queue("pay").build())
                .build());
        Assert.assertEquals(expiredTaskId, first.getTaskId());
        Thread.sleep(50L);

        String readyTaskId = backend.publish(LeasePublishRequest.builder()
                .queue("pay")
                .taskType("charge")
                .payload("ready")
                .priority(10)
                .build());

        LeaseGrant next = backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-b")
                .leaseMillis(100L)
                .waitTimeoutMillis(100L)
                .subscription(LeaseSubscription.builder().queue("pay").build())
                .build());

        Assert.assertNotNull(next);
        Assert.assertEquals(readyTaskId, next.getTaskId());
    }

    @Test
    public void testVersionStartsAtZeroAndIncrementsAcrossMutations() throws Exception {
        DataSource dataSource = JdbcLeaseBackendTestSupport.newDataSource();
        JdbcLeaseBackend backend = new JdbcLeaseBackend(dataSource);
        JdbcLeaseTaskDao dao = new JdbcLeaseTaskDao(Db.use(dataSource), new MySqlLeaseDbDialect(), new LeaseJsonCodec());
        String taskId = backend.publish(LeasePublishRequest.builder()
                .queue("pay")
                .taskType("charge")
                .payload("payload")
                .build());

        Assert.assertEquals(0L, dao.findById(taskId).getVersion());

        LeaseGrant grant = backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-a")
                .leaseMillis(200L)
                .waitTimeoutMillis(100L)
                .subscription(LeaseSubscription.builder().queue("pay").build())
                .build());
        Assert.assertNotNull(grant);
        Assert.assertEquals(1L, dao.findById(taskId).getVersion());

        backend.heartbeat(grant.getHandle(), 200L);
        Assert.assertEquals(2L, dao.findById(taskId).getVersion());

        backend.release(grant.getHandle(), com.team4u.framework.lease.model.LeaseReleaseRequest.of(0L));
        Assert.assertEquals(3L, dao.findById(taskId).getVersion());

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .payload("payload-v2")
                .build()));
        Assert.assertEquals(4L, dao.findById(taskId).getVersion());
    }

    private static final class FixedClock implements LongSupplier {

        private final long now;

        private FixedClock(long now) {
            this.now = now;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    private static final class SequenceClock implements LongSupplier {

        private final long[] values;
        private int index;

        private SequenceClock(long... values) {
            this.values = values;
        }

        @Override
        public synchronized long getAsLong() {
            if (index >= values.length) {
                return values[values.length - 1];
            }
            return values[index++];
        }
    }
}
