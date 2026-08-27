package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoff;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.dispatch.RetryDispatchCommand;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.record.RetryTransition;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.managed.store.record.RetryCreateRequest;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryTaskWorkerTest {

    @Test
    public void testBackgroundSuccessCompletesTask() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        TaskQueue queue = Leases.queue(backend, "retry-a");
        CountingHandler handler = new CountingHandler("payment", 0);
        RecoveryHandlerRegistry registry = registry(handler);
        RetryTaskWorker worker = new RetryTaskWorker(queue, registry);
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        String taskId = submitVisibleTask(store, "payment", "success", 0);
        worker.start();
        Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.SUCCEEDED));
    }

    @Test
    public void testBackgroundRetryTaskResultSchedulesNextAttempt() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        TaskQueue queue = Leases.queue(backend, "retry-b");
        CountingHandler handler = new CountingHandler("payment", 1);
        RetryTaskWorker worker = new RetryTaskWorker(queue, registry(handler));
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        String taskId = submitVisibleTask(store, "payment", "retry", 2, Backoffs.increment(50L, 0L));

        worker.start();
        Assert.assertTrue(awaitCalls(handler, 1));
        Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.PENDING));
        TaskSnapshot first = queue.get(taskId).get();
        RetryRecord firstRecord = store.get(taskId).get();
        Assert.assertEquals(1, first.getAttemptCount());
        Assert.assertEquals(1, firstRecord.getState().getAttempts());
        Assert.assertNotNull(firstRecord.getState().getNextRunAt());
        Assert.assertNotNull(firstRecord.getState().getLastErrorMessage());

        // Trigger deterministically after observing PENDING; do not rely on a 1ms visibility race.
        queue.reschedule(taskId, Duration.ZERO);
        Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.SUCCEEDED));
        Assert.assertEquals(2, handler.calls.get());
        RetryRecord persisted = store.get(taskId).get();
        Assert.assertEquals(2, persisted.getState().getAttempts());
        Assert.assertNotNull(persisted.getState().getSucceededAt());
        Assert.assertNull(persisted.getState().getNextRunAt());
        Assert.assertTrue(worker.shutdownGracefully(Duration.ofSeconds(2L)));
    }

    @Test
    public void testBackgroundExhaustedTaskResultFailsTask() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        TaskQueue queue = Leases.queue(backend, "retry-c");
        CountingHandler handler = new CountingHandler("payment", 3);
        RetryTaskWorker worker = new RetryTaskWorker(queue, registry(handler));
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        String taskId = submitVisibleTask(store, "payment", "exhausted", 2,
                Backoffs.increment(50L, 0L));

        worker.start();
        Assert.assertTrue(awaitCalls(handler, 1));
        Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.PENDING));
        queue.reschedule(taskId, Duration.ZERO);
        Assert.assertTrue(awaitCalls(handler, 2));
        // Observe the second write-back before scheduling the final attempt.
        Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.PENDING));
        queue.reschedule(taskId, Duration.ZERO);
        Assert.assertTrue(awaitCalls(handler, 3));
        Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.FAILED));
        Assert.assertEquals(3, handler.calls.get());
        RetryRecord persisted = store.get(taskId).get();
        Assert.assertEquals(RetryStatus.FAILED, persisted.getState().getStatus());
        Assert.assertEquals(3, persisted.getState().getAttempts());
        Assert.assertNotNull(persisted.getState().getFailedAt());
        Assert.assertNull(persisted.getState().getNextRunAt());
        Assert.assertTrue(worker.shutdownGracefully(Duration.ofSeconds(2L)));
    }

    @Test(timeout = 4000)
    public void testInfrastructureDeserializationFailureLeavesTaskRunningUntilLeaseExpiry()
            throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        TaskQueue queue = Leases.queue(backend, "retry-infrastructure");
        CountingHandler handler = new CountingHandler("payment", 0);
        RetryTaskWorker worker = new RetryTaskWorker(
                queue, registry(handler), new CorruptingSerializer());
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .foregroundMaxRetries(0)
                .backoff(Backoffs.fixed(1L))
                .build();
        RetryRecord record = RetryLeaseTestSupport.retryRecord(
                "payment", "corrupt", policy);
        RetryCreateRequest request = RetryCreateRequest.builder()
                .request(record.getRequest())
                .initialState(record.getState())
                .build();
        String taskId = store.createIfAbsent(request).getRecord().getTaskId();
        store.dispatch(RetryDispatchCommand.builder()
                .record(store.get(taskId).get())
                .transition(RetryTransition.builder()
                        .attempts(0)
                        .nextRunAt(Instant.now())
                        .build())
                .delayMillis(0L)
                .build());

        worker.start();
        long deadline = System.currentTimeMillis() + 3000L;
        TaskSnapshot snapshot;
        do {
            Thread.sleep(20L);
            snapshot = queue.get(taskId).get();
        } while (snapshot.getStatus() == TaskStatus.PENDING
                && System.currentTimeMillis() < deadline);

        Assert.assertEquals(TaskStatus.RUNNING, snapshot.getStatus());
        Assert.assertEquals(0, handler.calls.get());
        Assert.assertTrue(worker.shutdownGracefully(Duration.ofSeconds(2L)));
    }

    @Test
    public void testWorkerRegistrationIsLocalAndDuplicateTypeIsRejected() throws Exception {
        TaskQueue queue = Leases.queue(new InMemoryLeaseBackend(), "retry-registration");
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        CountingHandler initial = new CountingHandler("payment", 0);
        registry.register(initial);
        RetryTaskWorker worker = new RetryTaskWorker(queue, registry);
        worker.register(new CountingHandler("invoice", 0));
        CountingHandler lateRegistryHandler = new CountingHandler("shipment", 0);
        registry.register(lateRegistryHandler);

        try {
            worker.register(new CountingHandler("invoice", 9));
            Assert.fail("Expected duplicate handler rejection");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("invoice"));
        }
        worker.start();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(queue);
        String lateTaskId = submitVisibleTask(store, "shipment", "late-registry", 0);
        Assert.assertTrue(awaitStatus(queue, lateTaskId, TaskStatus.SUCCEEDED));
        String taskId = submitVisibleTask(store, "invoice", "not-overwritten", 0);
        Assert.assertTrue(awaitStatus(queue, taskId, TaskStatus.SUCCEEDED));
        try {
            worker.register(new CountingHandler("invoice", 9));
            Assert.fail("Expected post-start registration rejection");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("after RetryTaskWorker starts"));
        } finally {
            worker.close();
        }
        Assert.assertEquals(1, lateRegistryHandler.calls.get());
        Assert.assertEquals(2, registry.getPolicies().size());
    }

    @Test
    public void testWorkerRejectsMissingAndPostStartHandlers() {
        TaskQueue queue = Leases.queue(new InMemoryLeaseBackend(), "retry-empty");
        RetryTaskWorker emptyWorker = new RetryTaskWorker(queue, new RecoveryHandlerRegistry());
        try {
            emptyWorker.start();
            Assert.fail("Expected missing handler failure");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("at least one handler"));
        }

        CountingHandler handler = new CountingHandler("payment", 0);
        RetryTaskWorker worker = new RetryTaskWorker(queue, registry(handler));
        worker.register(new CountingHandler("invoice", 0));
        worker.start();
        try {
            worker.register(new CountingHandler("shipment", 0));
            Assert.fail("Expected post-start registration failure");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("after RetryTaskWorker starts"));
        } finally {
            worker.close();
        }
    }

    private static RecoveryHandlerRegistry registry(StringRecoveryHandler handler) {
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        registry.register(handler);
        return registry;
    }

    private static String submitVisibleTask(
            LeaseDurableRetryStore store,
            String taskType,
            String idempotencyKey,
            int maxRetries) {
        return submitVisibleTask(store, taskType, idempotencyKey, maxRetries,
                Backoffs.fixed(1L));
    }

    private static String submitVisibleTask(
            LeaseDurableRetryStore store,
            String taskType,
            String idempotencyKey,
            int maxRetries,
            Backoff backoff) {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(maxRetries)
                .foregroundMaxRetries(0)
                .backoff(backoff)
                .build();
        RetryRecord record = RetryLeaseTestSupport.retryRecord(
                taskType, idempotencyKey, policy);
        RetryCreateRequest request = RetryCreateRequest.builder()
                .request(record.getRequest())
                .initialState(record.getState())
                .build();
        String taskId = store.createIfAbsent(request).getRecord().getTaskId();
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

    private static boolean awaitCalls(CountingHandler handler, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            if (handler.calls.get() >= expected) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static final class CountingHandler implements StringRecoveryHandler {
        private final String name;
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingHandler(String name, int failures) {
            this.name = name;
            this.failures = failures;
        }

        @Override
        public String taskName() {
            return name;
        }

        @Override
        public void recover(String payload, RecoveryContext context) throws Exception {
            int call = calls.incrementAndGet();
            if (call <= failures) {
                throw new IllegalStateException("background call " + call + " failed");
            }
        }
    }

    private static final class CorruptingSerializer implements RetryRecordSerializer {
        @Override
        public String serialize(RetryRecord record) {
            throw new IllegalStateException("serializer down");
        }

        @Override
        public RetryRecord deserialize(String data) {
            throw new IllegalStateException("corrupt payload");
        }
    }
}
