package com.team4u.framework.lease;

import com.team4u.framework.lease.backoff.Backoff;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class LeaseWorkerTest {

    @Test
    public void testWorkerAcksOnSuccess() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> payloadRef = new AtomicReference<String>();

        registry.register(new LeaseTaskHandler() {
            @Override
            public String key() {
                return "pay";
            }

            @Override
            public void handle(String payload) {
                payloadRef.set(payload);
                latch.countDown();
            }
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .pollWaitMillis(50L)
                .build());
        worker.start("lease-worker-test-success");
        try {
            backend.publish("pay", "{\"id\":1}");
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assert.assertEquals("{\"id\":1}", payloadRef.get());

            Map<String, InMemoryLeaseBackend.StoredTask> snapshot = backend.snapshot();
            InMemoryLeaseBackend.StoredTask task = snapshot.values().iterator().next();
            Assert.assertEquals(LeaseTaskStatus.SUCCEEDED, task.getStatus());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testWorkerFailsAfterMaxAttempts() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        final CountDownLatch latch = new CountDownLatch(3);
        final AtomicInteger attempts = new AtomicInteger();

        registry.register(new LeaseTaskHandler() {
            @Override
            public String key() {
                return "pay";
            }

            @Override
            public void handle(String payload) {
                attempts.incrementAndGet();
                latch.countDown();
                throw new IllegalStateException("boom");
            }
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .maxAttempts(3)
                .backoff(Backoff.fixed(10L))
                .leaseMillis(100L)
                .pollWaitMillis(20L)
                .heartbeatEnabled(false)
                .build());
        worker.start("lease-worker-test-failure");
        try {
            String taskId = backend.publish("pay", "payload");
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));

            InMemoryLeaseBackend.StoredTask task = awaitTask(snapshotTask(taskId, backend), taskId, backend, LeaseTaskStatus.DEAD);
            Assert.assertEquals(3, attempts.get());
            Assert.assertEquals(LeaseTaskStatus.DEAD, task.getStatus());
            Assert.assertTrue(task.getLastError().contains("boom"));
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testMissingHandlerRetryLater() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .maxAttempts(2)
                .backoff(Backoff.fixed(20L))
                .pollWaitMillis(20L)
                .missingHandlerStrategy(MissingHandlerStrategy.RETRY_LATER)
                .build());

        worker.start("lease-worker-test-missing-handler");
        try {
            String taskId = backend.publish("missing", "payload");

            InMemoryLeaseBackend.StoredTask task = awaitTask(null, taskId, backend, LeaseTaskStatus.DEAD);
            Assert.assertEquals(LeaseTaskStatus.DEAD, task.getStatus());
            Assert.assertEquals(2, task.getAttemptCount());
        } finally {
            worker.shutdown();
        }
    }

    private InMemoryLeaseBackend.StoredTask snapshotTask(String taskId, InMemoryLeaseBackend backend) {
        return backend.snapshot().get(taskId);
    }

    private InMemoryLeaseBackend.StoredTask awaitTask(InMemoryLeaseBackend.StoredTask initial,
                                                      String taskId,
                                                      InMemoryLeaseBackend backend,
                                                      LeaseTaskStatus status) throws Exception {
        InMemoryLeaseBackend.StoredTask current = initial;
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            current = backend.snapshot().get(taskId);
            if (current != null && current.getStatus() == status) {
                return current;
            }
            Thread.sleep(20L);
        }
        return current;
    }
}
