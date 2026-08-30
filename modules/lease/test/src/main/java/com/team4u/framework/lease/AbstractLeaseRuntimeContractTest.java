package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public abstract class AbstractLeaseRuntimeContractTest extends AbstractLeaseContractSupport {

    @Test
    public void testSubmitWithoutDeduplicationKeyAlwaysCreatesTask() {
        LeaseBackend backend = createBackend();

        SubmitResult first = submitResult(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null,
                0L, Collections.<String, String>emptyMap());
        SubmitResult second = submitResult(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null,
                0L, Collections.<String, String>emptyMap());

        Assert.assertTrue(first.isCreated());
        Assert.assertTrue(second.isCreated());
        Assert.assertNotEquals(first.getTaskId(), second.getTaskId());
        Assert.assertEquals(TaskStatus.PENDING, first.getSnapshot().getStatus());
        Assert.assertEquals(TaskStatus.PENDING, second.getSnapshot().getStatus());
    }

    @Test
    public void testSubmitWithDeduplicationKeyIsIdempotentWithinQueueTypeAndKey() {
        LeaseBackend backend = createBackend();

        SubmitResult first = submitResult(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v1", "dedup-1",
                0L, Collections.<String, String>emptyMap());
        SubmitResult duplicate = submitResult(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v2", "dedup-1",
                0L, Collections.<String, String>emptyMap());
        SubmitResult differentKey = submitResult(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", "dedup-2",
                0L, Collections.<String, String>emptyMap());
        SubmitResult differentType = submitResult(backend, DEFAULT_QUEUE, MAIL_TASK_TYPE, "payload", "dedup-1",
                0L, Collections.<String, String>emptyMap());
        SubmitResult differentQueue = submitResult(backend, "invoices", PAY_TASK_TYPE, "payload", "dedup-1",
                0L, Collections.<String, String>emptyMap());

        Assert.assertTrue(first.isCreated());
        Assert.assertFalse(duplicate.isCreated());
        Assert.assertEquals(first.getTaskId(), duplicate.getTaskId());
        Assert.assertEquals("payload-v1", duplicate.getSnapshot().getPayload());
        Assert.assertTrue(differentKey.isCreated());
        Assert.assertTrue(differentType.isCreated());
        Assert.assertTrue(differentQueue.isCreated());
        Assert.assertEquals(first.getTaskId(), backend.getByDeduplicationKey(
                DEFAULT_QUEUE, PAY_TASK_TYPE, "dedup-1").get().getTaskId());
    }

    @Test
    public void testQueueTypeAndDeduplicationKeyAreCaseSensitive() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String lowerDedupTaskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "lower",
                "Case", 0L, Collections.<String, String>emptyMap());
        String upperDedupTaskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "upper",
                "case", 0L, Collections.<String, String>emptyMap(), 9);
        String capitalTypeTaskId = submit(backend, DEFAULT_QUEUE, "Pay", "capital-type");
        String capitalQueueTaskId = submit(backend, "Orders", PAY_TASK_TYPE, "capital-queue");

        Assert.assertNotEquals(lowerDedupTaskId, upperDedupTaskId);
        Assert.assertEquals(lowerDedupTaskId, backend.getByDeduplicationKey(
                DEFAULT_QUEUE, PAY_TASK_TYPE, "Case").get().getTaskId());
        Assert.assertEquals(upperDedupTaskId, backend.getByDeduplicationKey(
                DEFAULT_QUEUE, PAY_TASK_TYPE, "case").get().getTaskId());

        LeaseGrant firstPayTask = assertRunningGrant(acquire(backend, PAY_TASK_TYPE,
                WORKER_A, LEASE_MILLIS), upperDedupTaskId, WORKER_A);
        Assert.assertEquals("case", firstPayTask.getSnapshot().getDedupKey());
        LeaseGrant secondPayTask = assertRunningGrant(acquire(backend, PAY_TASK_TYPE,
                WORKER_A, LEASE_MILLIS), lowerDedupTaskId, WORKER_A);
        Assert.assertEquals("Case", secondPayTask.getSnapshot().getDedupKey());
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS));
        LeaseGrant capitalType = assertRunningGrant(
                acquire(backend, "Pay", WORKER_A, LONG_LEASE_MILLIS),
                capitalTypeTaskId, WORKER_A);
        Assert.assertEquals("Pay", capitalType.getSnapshot().getType());

        Assert.assertNull(acquireFromQueue(backend, "orders", PAY_TASK_TYPE, WORKER_B, LONG_LEASE_MILLIS));
        assertRunningGrant(acquireFromQueue(backend, "Orders", PAY_TASK_TYPE, WORKER_B, LONG_LEASE_MILLIS),
                capitalQueueTaskId, WORKER_B);
    }

    @Test
    public void testAcquireReturnsRunningGrant() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(PAY_TASK_TYPE, grant.getSnapshot().getType());
        Assert.assertEquals("payload", grant.getSnapshot().getPayload());
        Assert.assertEquals(1, grant.getSnapshot().getAttemptCount());
        Assert.assertNotNull(grant.getSnapshot().getLeaseExpiresAt());
    }

    @Test
    public void testPendingTaskIsNotVisibleBeforeDelay() throws InterruptedException {
        LeaseBackend backend = createBackend();
        SubmitResult result = submitResult(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null,
                SHORT_DELAY_MILLIS, Collections.<String, String>emptyMap());

        Assert.assertEquals(TaskStatus.PENDING, result.getSnapshot().getStatus());
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));

        waitUntilAfter(result.getSnapshot().getVisibleAt());
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS), result.getTaskId(), WORKER_A);
    }

    @Test
    public void testAcquireOnlyReturnsExactlySubscribedTaskType() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String mailTaskId = submit(backend, MAIL_TASK_TYPE, "mail-payload");

        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));

        LeaseGrant grant = assertRunningGrant(acquire(backend, MAIL_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                mailTaskId, WORKER_A);
        Assert.assertEquals(MAIL_TASK_TYPE, grant.getSnapshot().getType());
    }

    @Test
    public void testAcquireOnlyReturnsExactlySubscribedQueue() throws Exception {
        LeaseBackend backend = createBackend();
        submit(backend, "invoices", PAY_TASK_TYPE, "invoice-payload");

        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));
        Assert.assertNotNull(acquireFromQueue(backend, "invoices", PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS));
    }

    @Test
    public void testAcquireUsesGlobalPriorityThenCreatedAtOrderAcrossTaskTypes() throws Exception {
        LeaseBackend backend = createBackend();
        String lowPriorityTaskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "low", 0);
        waitUntilDistinctCreatedAt(backend, DEFAULT_QUEUE, lowPriorityTaskId);
        String firstHighPriorityTaskId = submit(backend, DEFAULT_QUEUE, MAIL_TASK_TYPE, "high-1", 9);
        waitUntilDistinctCreatedAt(backend, DEFAULT_QUEUE, firstHighPriorityTaskId);
        String secondHighPriorityTaskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "high-2", 9);

        Set<String> taskTypes = new LinkedHashSet<String>(Arrays.asList(
                PAY_TASK_TYPE, MAIL_TASK_TYPE));
        TaskSubscription subscription = TaskSubscription.of(DEFAULT_QUEUE, taskTypes);
        LeaseGrant first = backend.acquire(AcquireCommand.of(
                subscription, WORKER_A, LONG_LEASE_MILLIS));
        LeaseGrant second = backend.acquire(AcquireCommand.of(
                subscription, WORKER_A, LONG_LEASE_MILLIS));
        LeaseGrant third = backend.acquire(AcquireCommand.of(
                subscription, WORKER_A, LONG_LEASE_MILLIS));

        assertRunningGrant(first, firstHighPriorityTaskId, WORKER_A);
        assertRunningGrant(second, secondHighPriorityTaskId, WORKER_A);
        assertRunningGrant(third, lowPriorityTaskId, WORKER_A);
    }

    @Test
    public void testOnlyOneWorkerAcquiresSameTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant[] grants = new LeaseGrant[1];
        LeaseGrant[] competingGrant = new LeaseGrant[1];

        Thread first = startAcquire(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS, grants);
        waitUntil(new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                return snapshot(backend, DEFAULT_QUEUE, taskId).getStatus() == TaskStatus.RUNNING;
            }
        });
        Thread second = startAcquire(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, WORKER_B, LONG_LEASE_MILLIS,
                competingGrant);

        first.join(1_000L);
        second.join(1_000L);
        Assert.assertNull(competingGrant[0]);
        Assert.assertNotNull(grants[0]);
        Assert.assertEquals(1, snapshot(backend, DEFAULT_QUEUE, taskId).getAttemptCount());
    }

    @Test
    public void testAttemptCountIncreasesOnEachSuccessfulAcquire() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        LeaseGrant first = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(first.getHandle(),
                retry(0L, null, null, null)));

        LeaseGrant second = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS),
                taskId, WORKER_B);
        Assert.assertEquals(2, second.getSnapshot().getAttemptCount());
    }

    @Test
    public void testAttemptCountIncreasesWhenExpiredLeaseIsTakenOver() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant first = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);
        Instant originalExpiry = first.getSnapshot().getLeaseExpiresAt();

        waitUntilAfter(originalExpiry);
        LeaseGrant second = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LONG_LEASE_MILLIS),
                taskId, WORKER_B);
        Assert.assertEquals(2, second.getSnapshot().getAttemptCount());
    }

    @Test
    public void testHeartbeatExtendsLease() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED,
                backend.heartbeat(grant.getHandle(), LONG_LEASE_MILLIS));
        TaskSnapshot extended = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertTrue(extended.getLeaseExpiresAt().isAfter(grant.getSnapshot().getLeaseExpiresAt()));

        waitUntilAfter(grant.getSnapshot().getLeaseExpiresAt());
        Assert.assertEquals(TaskStatus.RUNNING, snapshot(backend, DEFAULT_QUEUE, taskId).getStatus());
        Assert.assertNull(acquire(backend, PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS));
    }

    @Test
    public void testAcquireAndHeartbeatDoNotChangeVisibleAt() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        Instant submittedVisibleAt = snapshot(backend, DEFAULT_QUEUE, taskId).getVisibleAt();

        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);
        Assert.assertEquals(submittedVisibleAt, grant.getSnapshot().getVisibleAt());
        Assert.assertEquals(submittedVisibleAt, snapshot(backend, DEFAULT_QUEUE, taskId).getVisibleAt());

        Assert.assertEquals(RuntimeResult.APPLIED,
                backend.heartbeat(grant.getHandle(), LONG_LEASE_MILLIS));
        TaskSnapshot heartbeatSnapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(submittedVisibleAt, heartbeatSnapshot.getVisibleAt());
        Assert.assertTrue(heartbeatSnapshot.getLeaseExpiresAt().isAfter(
                grant.getSnapshot().getLeaseExpiresAt()));
    }

    @Test
    public void testWrongLeaseTokenIsRejectedWithoutMutation() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS),
                taskId, WORKER_A);
        LeaseHandle wrongToken = LeaseHandle.of(taskId, WORKER_A, "wrong-token");

        TaskSnapshot before = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.heartbeat(wrongToken, LONG_LEASE_MILLIS));
        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.close(wrongToken,
                LeaseCompletion.succeeded(null, null)));
        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.release(wrongToken, retry(0L, null, null, null)));
        TaskSnapshot after = snapshot(backend, DEFAULT_QUEUE, taskId);

        Assert.assertEquals(before.getAttemptCount(), after.getAttemptCount());
        Assert.assertEquals(before.getLeaseExpiresAt(), after.getLeaseExpiresAt());
        Assert.assertEquals(before.getPayload(), after.getPayload());
        Assert.assertEquals(before.getErrorMessage(), after.getErrorMessage());
        Assert.assertEquals(before.getStatus(), after.getStatus());
    }

    @Test
    public void testStaleLeaseTokenIsRejectedWithoutMutation() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        LeaseGrant first = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        waitUntilAfter(first.getSnapshot().getLeaseExpiresAt());
        LeaseGrant second = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LONG_LEASE_MILLIS),
                taskId, WORKER_B);

        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.heartbeat(first.getHandle(), LONG_LEASE_MILLIS));
        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.close(first.getHandle(),
                LeaseCompletion.succeeded(null, null)));
        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.release(first.getHandle(),
                retry(0L, null, null, null)));
        Assert.assertEquals(WORKER_B, snapshot(backend, DEFAULT_QUEUE, taskId).getWorkerId());
        Assert.assertEquals(2, snapshot(backend, DEFAULT_QUEUE, taskId).getAttemptCount());
        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(second.getHandle(),
                LeaseCompletion.succeeded(null, null)));
    }

    @Test
    public void testReleaseAppliesRetryDelayPayloadErrorAndAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload-v1", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(grant.getHandle(),
                retry(SHORT_DELAY_MILLIS, "payload-v2", "retry", Collections.singletonMap("traceId", "T-2"))));

        TaskSnapshot pending = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, pending.getStatus());
        Assert.assertNull(pending.getWorkerId());
        Assert.assertNull(pending.getLeaseExpiresAt());
        Assert.assertEquals("payload-v2", pending.getPayload());
        Assert.assertEquals("retry", pending.getErrorMessage());
        Assert.assertEquals("T-2", pending.getAttributes().get("traceId"));
        Assert.assertEquals(1, pending.getAttemptCount());

        waitUntilAfter(pending.getVisibleAt());
        LeaseGrant next = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_B, LEASE_MILLIS),
                taskId, WORKER_B);
        Assert.assertEquals("payload-v2", next.getSnapshot().getPayload());
        Assert.assertEquals(2, next.getSnapshot().getAttemptCount());
    }

    @Test
    public void testReleaseWithoutAttributePatchKeepsAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(grant.getHandle(),
                retry(0L, "payload-v2", "retry", null)));

        TaskSnapshot pending = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals("payload-v2", pending.getPayload());
        Assert.assertEquals("T-1", pending.getAttributes().get("traceId"));
    }

    @Test
    public void testReleaseWithEmptyAttributePatchClearsAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null, 0L,
                Collections.singletonMap("traceId", "T-1"));
        LeaseGrant grant = assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(RuntimeResult.APPLIED, backend.release(grant.getHandle(),
                retry(0L, null, null, Collections.<String, String>emptyMap())));

        Assert.assertTrue(snapshot(backend, DEFAULT_QUEUE, taskId).getAttributes().isEmpty());
    }

    private LeaseRetry retry(long delayMillis, String payload, String errorMessage,
                             java.util.Map<String, String> attributes) {
        return LeaseRetry.of(delayMillis, payload, errorMessage, attributes);
    }

    /**
     * createdAt 时间戳为毫秒精度，同毫秒内提交的任务次序不确定；等两个任务戳不同即返回，
     * 取代固定 sleep(50)×2。
     */
    private void waitUntilDistinctCreatedAt(LeaseBackend backend, String queue,
                                             String firstTaskId) throws InterruptedException {
        final Instant firstCreatedAt = snapshot(backend, queue, firstTaskId).getCreatedAt();
        // 等待墙钟严格晚于前一任务的 createdAt 才返回：保证本方法返回后提交的下一个任务
        // 其 created_at（毫秒精度）必然落在不同毫秒，排序不会退化为 task_id 字典序。
        // 若仅等 ">= createdAt"或与提交同毫秒，同优先级下任务顺序将由随机 taskId 决定，
        // 优先级排序用例会闪断（曾见于 JdbcLeaseRuntimeContractTest）。
        LeaseTestWaits.awaitTrue(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return System.currentTimeMillis() > firstCreatedAt.toEpochMilli();
            }
        }, "clock did not advance past createdAt of task " + firstTaskId);
    }
}
