package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.dispatch.RetryDispatchCommand;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.managed.store.record.RetryCreateRequest;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.record.RetryTransition;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryLeaseInfrastructureRecoveryTest {

    @Test(timeout = 8000)
    public void testInterruptedRecoveryLeavesLeaseForRealTakeover() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        TaskQueue queue = Leases.queue(backend, "retry-interrupt-recovery");
        InterruptedHandler handler = new InterruptedHandler();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        registry.register(handler);
        RetryTaskWorker worker = new RetryTaskWorker(
                queue, registry, null, Duration.ofMillis(250L),
                Duration.ofMillis(10L), false, null, "interrupted-retry-worker",
                LeaseRetryRecordSerializer.INSTANCE);
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        String taskId = visibleTask(store, "payment", "interrupted");

        try {
            worker.start();
            Assert.assertTrue(awaitCalls(handler, 1));
            TaskSnapshot running = queue.get(taskId).get();
            Assert.assertEquals(TaskStatus.RUNNING, running.getStatus());

            long deadline = System.currentTimeMillis() + 3000L;
            TaskSnapshot snapshot = running;
            while (System.currentTimeMillis() < deadline) {
                snapshot = queue.get(taskId).get();
                if (snapshot.getStatus() == TaskStatus.PENDING
                        || snapshot.getStatus() == TaskStatus.FAILED) {
                    break;
                }
                Thread.sleep(20L);
            }
            Assert.assertEquals(TaskStatus.RUNNING, snapshot.getStatus());
            Assert.assertNull(store.get(taskId).get().getState().getFailedAt());

            // Wait past lease expiry, then prove the expired lease is acquirable by another worker.
            Instant expiresAt = snapshot.getLeaseExpiresAt();
            deadline = System.currentTimeMillis() + 3000L;
            while (Instant.now().isBefore(expiresAt) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            InterruptibleHandler replacement = new InterruptibleHandler();
            RecoveryHandlerRegistry replacementRegistry = new RecoveryHandlerRegistry();
            replacementRegistry.register(replacement);
            RetryTaskWorker replacementWorker = new RetryTaskWorker(
                    queue, replacementRegistry, "replacement-retry-worker",
                    Duration.ofSeconds(2L), Duration.ofMillis(10L), false, null,
                    "replacement-retry-worker", LeaseRetryRecordSerializer.INSTANCE);
            replacementWorker.start();
            try {
                Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.SUCCEEDED));
                Assert.assertEquals(1, replacement.calls().get());
            } finally {
                replacementWorker.shutdownNow();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    private static String visibleTask(
            LeaseDurableRetryStore store, String taskType, String idempotencyKey) {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .foregroundMaxRetries(0)
                .backoff(Backoffs.fixed(1L))
                .build();
        RetryRecord record = RetryLeaseTestSupport.retryRecord(taskType, idempotencyKey, policy);
        String taskId = store.createIfAbsent(RetryCreateRequest.builder()
                .request(record.getRequest())
                .initialState(record.getState())
                .build()).getRecord().getTaskId();
        store.dispatch(RetryDispatchCommand.builder()
                .record(store.get(taskId).get())
                .transition(RetryTransition.builder()
                        .attempts(0)
                        .nextRunAt(Instant.now())
                        .build())
                .delayMillis(0L)
                .build());
        return taskId;
    }

    private static boolean awaitCalls(CountingHandler handler, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            if (handler.calls().get() >= expected) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
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

    private interface CountingHandler extends StringRecoveryHandler {
        AtomicInteger calls();
    }

    private static final class InterruptedHandler implements CountingHandler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AtomicInteger calls() {
            return calls;
        }

        @Override
        public String taskName() {
            return "payment";
        }

        @Override
        public void recover(String payload, RecoveryContext context) throws Exception {
            calls.incrementAndGet();
            throw new InterruptedException("worker shutdown");
        }
    }

    private static final class InterruptibleHandler implements CountingHandler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AtomicInteger calls() {
            return calls;
        }

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
