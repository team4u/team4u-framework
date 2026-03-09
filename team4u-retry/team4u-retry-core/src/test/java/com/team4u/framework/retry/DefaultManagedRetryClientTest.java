package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultManagedRetryClient;
import com.team4u.framework.retry.client.RetryCoordinator;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RecoverySpec;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.store.DurableRetryStore;
import com.team4u.framework.retry.store.record.CancelRecord;
import com.team4u.framework.retry.store.record.FailureRecord;
import com.team4u.framework.retry.store.record.RetryRecord;
import com.team4u.framework.retry.store.record.SuccessRecord;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultManagedRetryClientTest {

    @Test
    public void testSubmitRejectsMissingRequiredFields() {
        RecordingStore store = new RecordingStore();
        RecordingCoordinator coordinator = new RecordingCoordinator();
        DefaultManagedRetryClient client = newClient(store, coordinator);
        RetryPolicy policy = retryPolicy(2, 1);

        assertRejected(client.submit(spec("task", "idem", null, RecoverySpec.of("recover", "payload"), policy)),
                "executor");
        assertRejected(client.submit(spec("task", " ", successTask("ok"), RecoverySpec.of("recover", "payload"), policy)),
                "idempotencyKey");
        assertRejected(client.submit(spec("task", "idem", successTask("ok"), RecoverySpec.of(" ", "payload"), policy)),
                "taskType");
    }

    @Test
    public void testForegroundSuccessMarksSucceeded() {
        RecordingStore store = new RecordingStore();
        RecordingCoordinator coordinator = new RecordingCoordinator();
        DefaultManagedRetryClient client = newClient(store, coordinator);

        ManagedSubmitResult<String> result = client.submit(spec(
                "payment",
                "order-1",
                successTask("done"),
                RecoverySpec.of("recover-payment", "payload"),
                retryPolicy(3, 2)));

        Assert.assertTrue(result instanceof ManagedSubmitResult.Completed);
        Assert.assertEquals("done", ((ManagedSubmitResult.Completed<String>) result).getValue());
        Assert.assertEquals(list("create", "markSucceeded"), store.operations);
        Assert.assertNotNull(store.successRecord);
        Assert.assertNull(coordinator.scheduledRecord);
    }

    @Test
    public void testForegroundExhaustedSchedulesBackgroundAndReturnsAccepted() {
        RecordingStore store = new RecordingStore();
        RecordingCoordinator coordinator = new RecordingCoordinator();
        DefaultManagedRetryClient client = newClient(store, coordinator);
        AtomicInteger attempts = new AtomicInteger();

        ManagedSubmitResult<String> result = client.submit(spec(
                "payment",
                "order-2",
                () -> {
                    attempts.incrementAndGet();
                    throw new IOException("boom");
                },
                RecoverySpec.of("recover-payment", "payload"),
                retryPolicy(3, 2)));

        Assert.assertTrue(result instanceof ManagedSubmitResult.Accepted);
        ManagedSubmitResult.Accepted<String> accepted = (ManagedSubmitResult.Accepted<String>) result;
        Assert.assertEquals("task-1", accepted.getTaskId());
        Assert.assertEquals(RetryStatus.SCHEDULED.name(), accepted.getState());
        Assert.assertNotNull(accepted.getNextAttemptAt());
        Assert.assertEquals(2, attempts.get());
        Assert.assertEquals(list("create"), store.operations);
        Assert.assertNotNull(coordinator.scheduledRecord);
        Assert.assertEquals(RetryStatus.SCHEDULED, coordinator.scheduledRecord.getState().getStatus());
        Assert.assertEquals(accepted.getNextAttemptAt(), coordinator.scheduledRecord.getState().getNextRunAt());
        Assert.assertEquals(2, coordinator.scheduledRecord.getState().getAttempts());
        Assert.assertEquals("IOException", coordinator.scheduledRecord.getState().getLastErrorCode());
        Assert.assertEquals("boom", coordinator.scheduledRecord.getState().getLastErrorMessage());
        Assert.assertEquals(0L, coordinator.delayMillis);
        Assert.assertNull(store.failedRecord);
    }

    @Test
    public void testInterruptedForegroundBackoffMarksFailed() {
        RecordingStore store = new RecordingStore();
        RecordingCoordinator coordinator = new RecordingCoordinator();
        DefaultManagedRetryClient client = newClient(store, coordinator);

        try {
            Thread.currentThread().interrupt();

            ManagedSubmitResult<String> result = client.submit(spec(
                    "payment",
                    "order-3",
                    () -> {
                        throw new IOException("retry-me");
                    },
                    RecoverySpec.of("recover-payment", "payload"),
                    retryPolicy(3, 2, 10L)));

            Assert.assertTrue(result instanceof ManagedSubmitResult.Failed);
            Throwable error = ((ManagedSubmitResult.Failed<String>) result).getError();
            Assert.assertTrue(error instanceof InterruptedException);
            Assert.assertTrue(Thread.currentThread().isInterrupted());
            Assert.assertEquals(list("create", "markFailed"), store.operations);
            Assert.assertNotNull(store.failedRecord);
            Assert.assertEquals("InterruptedException", store.failedRecord.getErrorCode());
            Assert.assertNull(coordinator.scheduledRecord);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testErrorDoesNotRetryOrMarkFailed() {
        RecordingStore store = new RecordingStore();
        RecordingCoordinator coordinator = new RecordingCoordinator();
        DefaultManagedRetryClient client = newClient(store, coordinator);

        try {
            client.submit(spec(
                    "payment",
                    "order-4",
                    () -> {
                        throw new OutOfMemoryError("oom");
                    },
                    RecoverySpec.of("recover-payment", "payload"),
                    retryPolicy(3, 2)));
            Assert.fail("expected OutOfMemoryError");
        } catch (OutOfMemoryError error) {
            Assert.assertEquals("oom", error.getMessage());
        }

        Assert.assertEquals(list("create"), store.operations);
        Assert.assertNull(store.failedRecord);
        Assert.assertNull(coordinator.scheduledRecord);
    }

    private DefaultManagedRetryClient newClient(RecordingStore store, RecordingCoordinator coordinator) {
        return DefaultManagedRetryClient.builder()
                .store(store)
                .coordinator(coordinator)
                .build();
    }

    private RetryPolicy retryPolicy(int maxAttempts, int foregroundAttempts) {
        return retryPolicy(maxAttempts, foregroundAttempts, 0L);
    }

    private RetryPolicy retryPolicy(int maxAttempts, int foregroundAttempts, long delayMillis) {
        return RetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .foregroundAttempts(foregroundAttempts)
                .backoff(Backoffs.fixed(delayMillis))
                .retryOn(IOException.class)
                .build();
    }

    private Callable<String> successTask(String value) {
        return () -> value;
    }

    private RetryTaskSpec<String> spec(
            String ignoredTaskName,
            String idempotencyKey,
            Callable<String> executor,
            RecoverySpec recoverySpec,
            RetryPolicy policy) {
        return RetryTaskSpec.<String>builder()
                .idempotencyKey(idempotencyKey)
                .executor(executor)
                .recovery(recoverySpec)
                .policy(policy)
                .build();
    }

    private void assertRejected(ManagedSubmitResult<String> result, String reasonPart) {
        Assert.assertTrue(result instanceof ManagedSubmitResult.Rejected);
        Assert.assertTrue(((ManagedSubmitResult.Rejected<String>) result).getReason().contains(reasonPart));
    }

    private List<String> list(String... items) {
        List<String> values = new ArrayList<String>();
        Collections.addAll(values, items);
        return values;
    }

    private static class RecordingStore implements DurableRetryStore {
        private final List<String> operations = new ArrayList<String>();
        private SuccessRecord successRecord;
        private FailureRecord failedRecord;

        @Override
        public String create(RetryRecord initialRecord) {
            operations.add("create");
            return "task-1";
        }

        @Override
        public void markSucceeded(String taskId, SuccessRecord success) {
            operations.add("markSucceeded");
            successRecord = success;
        }

        @Override
        public void markFailed(String taskId, FailureRecord failure) {
            operations.add("markFailed");
            failedRecord = failure;
        }

        @Override
        public void cancel(String taskId, CancelRecord cancel) {
            operations.add("cancel");
        }
    }

    private static class RecordingCoordinator implements RetryCoordinator {
        private RetryRecord scheduledRecord;
        private long delayMillis;

        @Override
        public void schedule(RetryRecord record, long delayMillis) {
            this.scheduledRecord = record;
            this.delayMillis = delayMillis;
        }
    }
}
