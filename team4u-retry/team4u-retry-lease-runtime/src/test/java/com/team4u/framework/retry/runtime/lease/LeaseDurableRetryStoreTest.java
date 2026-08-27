package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.TaskContext;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.TaskSubscription;
import com.team4u.framework.retry.api.RecoverySpec;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.managed.dispatch.RetryDispatchCommand;
import com.team4u.framework.retry.managed.model.RetryRequest;
import com.team4u.framework.retry.managed.model.RetryState;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.store.record.CancelRecord;
import com.team4u.framework.retry.managed.store.record.FailureRecord;
import com.team4u.framework.retry.managed.store.record.RetryCreateRequest;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.record.RetryTransition;
import com.team4u.framework.retry.managed.store.record.SubmitRecord;
import com.team4u.framework.retry.managed.store.record.SuccessRecord;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

public class LeaseDurableRetryStoreTest {

    @Test
    public void testRawDedupKeyCoexistsAcrossTaskTypes() {
        TaskQueue queue = queue();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);

        SubmitRecord first = create(store, "payment", "same-key");
        SubmitRecord second = create(store, "invoice", "same-key");
        SubmitRecord succeeded = create(store, "payment", "success");
        Instant succeededAt = Instant.now();
        store.markSucceeded(succeeded.getRecord().getTaskId(),
                SuccessRecord.builder().succeededAt(succeededAt).attempts(3).build());
        assertStatus(queue, succeeded.getRecord().getTaskId(), TaskStatus.SUCCEEDED,
                RetryStatus.SUCCEEDED);
        RetryRecord persistedSuccess = store.get(succeeded.getRecord().getTaskId()).get();
        Assert.assertEquals(3, persistedSuccess.getState().getAttempts());
        Assert.assertEquals(succeededAt, persistedSuccess.getState().getSucceededAt());
        Assert.assertNull(persistedSuccess.getState().getNextRunAt());

        SubmitRecord failed = create(store, "payment", "failure");
        Instant failedAt = Instant.now();
        store.markFailed(failed.getRecord().getTaskId(), FailureRecord.builder()
                .errorCode("IllegalStateException")
                .errorMessage("foreground exhausted")
                .failedAt(failedAt)
                .attempts(2)
                .build());
        assertStatus(queue, failed.getRecord().getTaskId(), TaskStatus.FAILED,
                RetryStatus.FAILED);
        RetryRecord persistedFailure = store.get(failed.getRecord().getTaskId()).get();
        Assert.assertEquals(2, persistedFailure.getState().getAttempts());
        Assert.assertEquals(failedAt, persistedFailure.getState().getFailedAt());
        Assert.assertEquals("foreground exhausted", persistedFailure.getState().getLastErrorMessage());
        Assert.assertNull(persistedFailure.getState().getNextRunAt());

        SubmitRecord cancelled = create(store, "payment", "cancel");
        Instant cancelledAt = Instant.now();
        store.markCancelled(cancelled.getRecord().getTaskId(), CancelRecord.builder()
                .reason("manual cancellation")
                .cancelledAt(cancelledAt)
                .build());
        assertStatus(queue, cancelled.getRecord().getTaskId(), TaskStatus.CANCELLED,
                RetryStatus.CANCELLED);
        RetryRecord persistedCancel = store.get(cancelled.getRecord().getTaskId()).get();
        Assert.assertNotNull(persistedCancel.getState().getCancelledAt());
        Assert.assertNull(persistedCancel.getState().getNextRunAt());
    }

    @Test
    public void testGetUsesQueueScopedTaskId() {
        TaskQueue queue = queue();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        SubmitRecord created = create(store, "payment", "id-1");

        Optional<RetryRecord> found = store.get(created.getRecord().getTaskId());

        Assert.assertTrue(found.isPresent());
        Assert.assertEquals("payment", found.get().getRequest().getTaskType());
        Assert.assertEquals(created.getRecord().getTaskId(),
                found.get().getRequest().getTaskId());
    }

    @Test
    public void testForegroundTerminalResultsPersistRetryRecord() {
        TaskQueue queue = queue();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);

        SubmitRecord succeeded = create(store, "payment", "success");
        Instant succeededAt = Instant.now();
        store.markSucceeded(succeeded.getRecord().getTaskId(),
                SuccessRecord.builder().succeededAt(succeededAt).attempts(3).build());
        assertStatus(queue, succeeded.getRecord().getTaskId(), TaskStatus.SUCCEEDED,
                RetryStatus.SUCCEEDED);
        RetryRecord persistedSuccess = store.get(succeeded.getRecord().getTaskId()).get();
        Assert.assertEquals(3, persistedSuccess.getState().getAttempts());
        Assert.assertEquals(succeededAt, persistedSuccess.getState().getSucceededAt());
        Assert.assertNull(persistedSuccess.getState().getNextRunAt());

        SubmitRecord failed = create(store, "payment", "failure");
        Instant failedAt = Instant.now();
        store.markFailed(failed.getRecord().getTaskId(), FailureRecord.builder()
                .errorCode("IllegalStateException")
                .errorMessage("foreground exhausted")
                .failedAt(failedAt)
                .attempts(2)
                .build());
        assertStatus(queue, failed.getRecord().getTaskId(), TaskStatus.FAILED,
                RetryStatus.FAILED);
        RetryRecord persistedFailure = store.get(failed.getRecord().getTaskId()).get();
        Assert.assertEquals(2, persistedFailure.getState().getAttempts());
        Assert.assertEquals(failedAt, persistedFailure.getState().getFailedAt());
        Assert.assertEquals("foreground exhausted", persistedFailure.getState().getLastErrorMessage());
        Assert.assertNull(persistedFailure.getState().getNextRunAt());

        SubmitRecord cancelled = create(store, "payment", "cancel");
        Instant cancelledAt = Instant.now();
        store.markCancelled(cancelled.getRecord().getTaskId(), CancelRecord.builder()
                .reason("manual cancellation")
                .cancelledAt(cancelledAt)
                .build());
        assertStatus(queue, cancelled.getRecord().getTaskId(), TaskStatus.CANCELLED,
                RetryStatus.CANCELLED);
        RetryRecord persistedCancel = store.get(cancelled.getRecord().getTaskId()).get();
        Assert.assertNotNull(persistedCancel.getState().getCancelledAt());
        Assert.assertNull(persistedCancel.getState().getNextRunAt());
    }

    @Test
    public void testDispatchUpdatesPayloadAndReschedules() {
        TaskQueue queue = queue();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        SubmitRecord created = create(store, "payment", "dispatch");
        RetryRecord record = created.getRecord();
        Instant nextRunAt = Instant.now().plusMillis(5000L);

        store.dispatch(RetryDispatchCommand.builder()
                .record(record)
                .transition(RetryTransition.builder()
                        .attempts(2)
                        .nextRunAt(nextRunAt)
                        .lastErrorCode("IllegalStateException")
                        .lastErrorMessage("boom")
                        .build())
                .delayMillis(5000L)
                .build());

        TaskSnapshot snapshot = queue.get(record.getTaskId()).get();
        RetryRecord persisted = new LeaseRetryRecordSerializer().deserialize(snapshot.getPayload());
        Assert.assertEquals(record.getTaskId(), persisted.getTaskId());
        Assert.assertEquals(record.getTaskId(), persisted.getRequest().getTaskId());
        Assert.assertEquals(TaskStatus.PENDING, snapshot.getStatus());
        Assert.assertEquals(RetryStatus.WAITING_RETRY, persisted.getState().getStatus());
        Assert.assertEquals(2, persisted.getState().getAttempts());
        Assert.assertEquals(nextRunAt, persisted.getState().getNextRunAt());
        Assert.assertEquals(record.getTaskId(), persisted.getState().getBackendTaskId());
        Assert.assertEquals("IllegalStateException", persisted.getState().getLastErrorCode());
        Assert.assertEquals("boom", persisted.getState().getLastErrorMessage());
    }

    @Test(expected = TaskQueueOperationException.class)
    public void testSecondTerminalCompletionFailsFast() {
        TaskQueue queue = queue();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        SubmitRecord created = create(store, "payment", "terminal-conflict");
        store.markSucceeded(created.getRecord().getTaskId(),
                SuccessRecord.builder().succeededAt(Instant.now()).build());

        store.markCancelled(created.getRecord().getTaskId(), CancelRecord.builder()
                .reason("too late").cancelledAt(Instant.now()).build());
    }

    @Test(timeout = 5000)
    public void testForegroundRecoveryTimeoutMakesCrashedIntentVisibleToWorker() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        TaskQueue queue = Leases.queue(
                backend, RetryTaskQueues.DEFAULT_RECOVERY_QUEUE);
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(
                queue, LeaseRetryRecordSerializer.INSTANCE, Duration.ofMillis(250L));
        Instant before = Instant.now();
        SubmitRecord created = create(store, "payment", "crashed-intent");
        String taskId = created.getRecord().getTaskId();

        TaskSnapshot snapshot = queue.get(taskId).get();
        Assert.assertEquals(RetryStatus.ACCEPTED, created.getRecord().getState().getStatus());
        Assert.assertNotNull(created.getRecord().getState().getNextRunAt());
        Assert.assertTrue(!created.getRecord().getState().getNextRunAt().isBefore(before.plusMillis(250L)));
        LeaseRetryRecordSerializer.INSTANCE.deserialize(snapshot.getPayload());
        long deadline = System.currentTimeMillis() + 3000L;
        do {
            Thread.sleep(20L);
            snapshot = queue.get(taskId).get();
        } while (snapshot.getStatus() == TaskStatus.PENDING
                && snapshot.getVisibleAt().isAfter(Instant.now())
                && System.currentTimeMillis() < deadline);
        Assert.assertTrue("foreground recovery intent did not become visible",
                !snapshot.getVisibleAt().isAfter(Instant.now()));

        // A worker can acquire the formerly hidden intent after the bounded foreground window.
        LeaseGrant grant = backend.acquire(
                AcquireCommand.of(
                        TaskSubscription.of(queue.name(), Collections.singleton("payment")),
                        "recovery-worker", 500L));
        try {
            Assert.assertNotNull(grant);
            Assert.assertEquals(taskId, grant.getSnapshot().getTaskId());
        } finally {
            queue.complete(taskId, TaskResult.success(
                    snapshot.getPayload(), Collections.<String, String>emptyMap()));
        }
    }

    @Test
    public void testForegroundRecoveryTimeoutIsValidatedExactly() {
        assertTimeoutRejected(null);
        assertTimeoutRejected(Duration.ZERO);
        assertTimeoutRejected(Duration.ofSeconds(-1L));
        assertTimeoutRejected(Duration.ofMillis(250L).plusNanos(1L));
        Assert.assertEquals(300000L,
                new LeaseDurableRetryStore(queue()).foregroundRecoveryTimeoutMillis());
    }

    @Test
    public void testCreateIfAbsentValidatesCompleteDurableIntent() {
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue());
        RetryRecord record = RetryLeaseTestSupport.retryRecord(
                "payment", "valid", RetryLeaseTestSupport.retryPolicy(2, 10L));
        RetryRecord missingTaskType = cloneRequest(record, request -> request.setTaskType(" "));
        assertCreateRejected(store, missingTaskType, "taskType");
        RetryRecord missingRecovery = cloneRequest(record,
                request -> request.setRecovery(RecoverySpec.of(" ", "payload")));
        assertCreateRejected(store, missingRecovery, "recovery.taskType");
        RetryRecord missingPolicy = cloneRequest(record, request -> request.setPolicy(null));
        assertCreateRejected(store, missingPolicy, "policy");
        RetryRecord missingCreatedAt = cloneRequest(record, request -> request.setCreatedAt(null));
        assertCreateRejected(store, missingCreatedAt, "createdAt");
        RetryRecord missingState = RetryRecord.builder().request(record.getRequest()).build();
        assertCreateRejected(store, missingState, "initialState");
    }

    private static void assertTimeoutRejected(Duration timeout) {
        try {
            new LeaseDurableRetryStore(queue(), LeaseRetryRecordSerializer.INSTANCE, timeout);
            Assert.fail("Expected invalid foreground recovery timeout rejection");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("foregroundRecoveryTimeout"));
        }
    }

    private static RetryRecord cloneRequest(
            RetryRecord source, java.util.function.Consumer<RetryRequest> mutator) {
        RetryRequest request = RetryRequest.builder()
                .taskId(source.getRequest().getTaskId())
                .taskType(source.getRequest().getTaskType())
                .idempotencyKey(source.getRequest().getIdempotencyKey())
                .recovery(source.getRequest().getRecovery())
                .policy(source.getRequest().getPolicy())
                .createdAt(source.getRequest().getCreatedAt())
                .build();
        mutator.accept(request);
        return RetryRecord.builder()
                .taskId(source.getTaskId())
                .request(request)
                .state(source.getState())
                .build();
    }

    private static void assertCreateRejected(
            LeaseDurableRetryStore store, RetryRecord record, String messagePart) {
        try {
            store.createIfAbsent(RetryCreateRequest.builder()
                    .request(record.getRequest())
                    .initialState(record.getState())
                    .build());
            Assert.fail("Expected invalid RetryCreateRequest rejection: " + messagePart);
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private static TaskQueue queue() {
        return Leases.queue(new InMemoryLeaseBackend(), RetryTaskQueues.DEFAULT_RECOVERY_QUEUE);
    }

    private static SubmitRecord create(
            LeaseDurableRetryStore store,
            String taskType,
            String idempotencyKey) {
        RetryPolicy policy = RetryLeaseTestSupport.retryPolicy(2, 10L);
        RetryRecord record = RetryLeaseTestSupport.retryRecord(taskType, idempotencyKey, policy);
        return store.createIfAbsent(RetryCreateRequest.builder()
                .request(record.getRequest())
                .initialState(record.getState())
                .build());
    }

    private static void assertStatus(
            TaskQueue queue,
            String taskId,
            TaskStatus taskStatus,
            RetryStatus retryStatus) {
        TaskSnapshot snapshot = queue.get(taskId).get();
        Assert.assertEquals(taskStatus, snapshot.getStatus());
    }
}
