package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.api.ManagedSubmitResult;
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ManagedRetryRuntimeTest {

    @Test
    public void testStartCloseRunsAcceptedTaskOnConfiguredQueue() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        FlakyHandler handler = new FlakyHandler(1);
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        registry.register(handler);

        ManagedRetryRuntime runtime = runtime(backend, registry, null, 2, 0, true);
        try {
            Assert.assertTrue(runtime.worker().isStarted());
            Assert.assertEquals("managed-retry", runtime.queueName());
            Assert.assertEquals("managed-retry", runtime.worker().queueName());

            ManagedSubmitResult<String> accepted = submit(runtime,
                    new RecordingExecutor(1), "order-1", 2, 0);

            Assert.assertTrue(accepted.isAccepted());
            String taskId = ((ManagedSubmitResult.Accepted<String>) accepted).getTaskId();
            TaskQueue queue = Leases.queue(backend, "managed-retry");
            Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.SUCCEEDED));
            Assert.assertEquals(2, handler.calls.get());
        } finally {
            runtime.close();
        }
        Assert.assertFalse(runtime.worker().isStarted());
    }

    @Test
    public void testCustomSerializerIsUsedByStoreAndWorker() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        CountingSerializer serializer = new CountingSerializer();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        registry.register(new RecordingHandler());
        ManagedRetryRuntime runtime = runtime(backend, registry, serializer, 2, 0, true);

        try {
            ManagedSubmitResult<String> accepted = submit(runtime,
                    new RecordingExecutor(9), "custom-serializer", 2, 0);
            Assert.assertTrue(accepted.isAccepted());
            String taskId = ((ManagedSubmitResult.Accepted<String>) accepted).getTaskId();
            TaskQueue queue = Leases.queue(backend, "managed-retry");
            Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.SUCCEEDED));
            Assert.assertTrue(serializer.serializeCount.get() >= 2);
            Assert.assertTrue(serializer.deserializeCount.get() >= 1);
        } finally {
            runtime.close();
        }
    }

    @Test
    public void testForegroundSuccessPersistsAttemptsAndTimestamp() {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        ManagedRetryRuntime runtime = runtime(
                backend, new RecoveryHandlerRegistry(), null, 2, 0, false);
        try {
            ManagedSubmitResult<String> result = submit(runtime,
                    new RecordingExecutor(0), "foreground-success", 2, 0);
            Assert.assertTrue(result.isCompleted());

            TaskSnapshot snapshot = Leases.queue(backend, "managed-retry")
                    .get("payment", "foreground-success").get();
            Assert.assertSame(TaskStatus.SUCCEEDED, snapshot.getStatus());
            RetryRecord record = LeaseRetryRecordSerializer.INSTANCE.deserialize(
                    snapshot.getPayload());
            Assert.assertEquals(1, record.getState().getAttempts());
            Assert.assertEquals(RetryStatus.SUCCEEDED, record.getState().getStatus());
            Assert.assertNotNull(record.getState().getSucceededAt());
            Assert.assertNull(record.getState().getNextRunAt());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void testForegroundExhaustionFailsDurableTask() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        ManagedRetryRuntime runtime = runtime(
                backend, new RecoveryHandlerRegistry(), null, 1, 1, false);
        RecordingExecutor executor = new RecordingExecutor(9);

        try {
            ManagedSubmitResult<String> result = submit(runtime, executor,
                    "foreground-failed", 1, 1);
            Assert.assertTrue(result.isFailed());
            Assert.assertEquals(2, executor.calls.get());

            TaskQueue queue = Leases.queue(backend, "managed-retry");
            TaskSnapshotResult task = task(queue);
            Assert.assertSame(TaskStatus.FAILED, task.status());
            RetryRecord record = LeaseRetryRecordSerializer.INSTANCE.deserialize(task.payload());
            Assert.assertEquals(2, record.getState().getAttempts());
            Assert.assertEquals(RetryStatus.FAILED, record.getState().getStatus());
            Assert.assertNotNull(record.getState().getFailedAt());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void testForegroundAttemptsContinueInBackgroundWithoutExceedingMaxRetries()
            throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        RecordingHandler handler = new RecordingHandler();
        registry.register(handler);
        ManagedRetryRuntime runtime = runtime(backend, registry, null, 2, 1, true);
        RecordingExecutor executor = new RecordingExecutor(2);

        try {
            ManagedSubmitResult<String> result = submit(runtime, executor,
                    "foreground-handoff", 2, 1);
            Assert.assertTrue(result.isAccepted());
            String taskId = ((ManagedSubmitResult.Accepted<String>) result).getTaskId();
            TaskQueue queue = Leases.queue(backend, "managed-retry");
            Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.SUCCEEDED));
            Assert.assertEquals(2, executor.calls.get());
            Assert.assertEquals(1, handler.calls.get());

            RetryRecord record = LeaseRetryRecordSerializer.INSTANCE.deserialize(
                    queue.get(taskId).get().getPayload());
            Assert.assertEquals(3, record.getState().getAttempts());
            Assert.assertEquals(RetryStatus.SUCCEEDED, record.getState().getStatus());
            Assert.assertNotNull(record.getState().getSucceededAt());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void testStartFailsWithoutHandlerAndBlankQueue() {
        ManagedRetryRuntime runtime = runtime(
                new InMemoryLeaseBackend(), new RecoveryHandlerRegistry(), null, 2, 0, false);
        try {
            runtime.start();
            Assert.fail("Expected missing handler failure");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("at least one handler"));
        } finally {
            runtime.close();
        }

        try {
            ManagedRetryRuntime.lease(new InMemoryLeaseBackend()).queueName(" ");
            Assert.fail("Expected blank queue failure");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("queueName"));
        }
    }

    private static ManagedRetryRuntime runtime(
            InMemoryLeaseBackend backend,
            RecoveryHandlerRegistry registry,
            RetryRecordSerializer serializer,
            int maxRetries,
            int foregroundMaxRetries,
            boolean start) {
        ManagedRetryRuntime.Builder builder = ManagedRetryRuntime.lease(backend)
                .registry(registry)
                .autoScanRecoveryHandlers(false)
                .queueName("managed-retry")
                .defaultPolicy(policy(maxRetries, foregroundMaxRetries))
                .workerId("retry-worker-test")
                .lease(Duration.ofSeconds(2L))
                .pollInterval(Duration.ofMillis(10L))
                .heartbeatEnabled(false)
                .threadName("managed-retry-worker");
        if (serializer != null) {
            builder.serializer(serializer);
        }
        ManagedRetryRuntime runtime = builder.build();
        if (start) {
            runtime.start();
        }
        return runtime;
    }

    private static RetryPolicy policy(int maxRetries, int foregroundMaxRetries) {
        return RetryPolicy.builder()
                .maxRetries(maxRetries)
                .foregroundMaxRetries(foregroundMaxRetries)
                .backoff(Backoffs.fixed(1L))
                .build();
    }

    private static ManagedSubmitResult<String> submit(
            ManagedRetryRuntime runtime,
            RecordingExecutor executor,
            String idempotencyKey,
            int maxRetries,
            int foregroundMaxRetries) {
        return Retries.managed(runtime.client())
                .taskType("payment")
                .idempotencyKey(idempotencyKey)
                .payload("payload-" + idempotencyKey)
                .policy(policy(maxRetries, foregroundMaxRetries))
                .call(executor);
    }

    private static TaskSnapshotResult task(TaskQueue queue) {
        // A foreground final failure does not expose taskId in the public result; the stable
        // idempotency key still identifies the durable record.
        TaskSnapshot snapshot = queue.get("payment", "foreground-failed").get();
        return new TaskSnapshotResult(snapshot.getStatus(), snapshot.getPayload());
    }

    private static boolean awaitStatus(TaskQueue queue, String taskId, TaskStatus status)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            if (queue.get(taskId).get().getStatus() == status) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static final class TaskSnapshotResult {
        private final TaskStatus status;
        private final String payload;

        private TaskSnapshotResult(TaskStatus status, String payload) {
            this.status = status;
            this.payload = payload;
        }

        private TaskStatus status() {
            return status;
        }

        private String payload() {
            return payload;
        }
    }

    private static final class CountingSerializer implements RetryRecordSerializer {
        private final RetryRecordSerializer delegate =
                LeaseRetryRecordSerializer.INSTANCE;
        private final AtomicInteger serializeCount = new AtomicInteger();
        private final AtomicInteger deserializeCount = new AtomicInteger();

        @Override
        public String serialize(RetryRecord record) {
            serializeCount.incrementAndGet();
            return delegate.serialize(record);
        }

        @Override
        public RetryRecord deserialize(String data) {
            deserializeCount.incrementAndGet();
            return delegate.deserialize(data);
        }
    }

    private static final class RecordingExecutor implements java.util.concurrent.Callable<String> {
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingExecutor(int failures) {
            this.failures = failures;
        }

        @Override
        public String call() {
            int call = calls.incrementAndGet();
            if (call <= failures) {
                throw new IllegalStateException("foreground call " + call + " failed");
            }
            return "done";
        }
    }

    private static final class FlakyHandler implements StringRecoveryHandler {
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        private FlakyHandler(int failures) {
            this.failures = failures;
        }

        @Override
        public String taskName() {
            return "payment";
        }

        @Override
        public void recover(String payload, RecoveryContext context) throws Exception {
            if (calls.incrementAndGet() <= failures) {
                throw new IllegalStateException("background failed");
            }
        }
    }

    private static final class RecordingHandler implements StringRecoveryHandler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String taskName() {
            return "payment";
        }

        @Override
        public void recover(String payload, RecoveryContext context) {
            calls.incrementAndGet();
        }
    }
}
