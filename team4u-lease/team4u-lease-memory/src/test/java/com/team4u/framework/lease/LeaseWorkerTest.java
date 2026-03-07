package com.team4u.framework.lease;

import com.team4u.framework.lease.backoff.Backoff;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class LeaseWorkerTest {

    private static final String DEFAULT_QUEUE = "default";

    @Test
    public void testWorkerAcksOnSuccess() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> payloadRef = new AtomicReference<String>();

        registry.register(DEFAULT_QUEUE, "pay", context -> {
            payloadRef.set(context.getPayload());
            latch.countDown();
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .pollWaitMillis(50L)
                .build());
        worker.start("lease-worker-test-success");
        try {
            backend.publish(request("pay", "{\"id\":1}"));
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assert.assertEquals("{\"id\":1}", payloadRef.get());
            Assert.assertEquals(LeaseTaskStatus.SUCCEEDED, backend.snapshot().values().iterator().next().getStatus());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testWorkerRetriesByFailureCountAndMarksDead() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger attempts = new AtomicInteger();

        registry.register(DEFAULT_QUEUE, "pay", context -> {
            attempts.incrementAndGet();
            latch.countDown();
            throw new IllegalStateException("boom");
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .maxFailures(3)
                .backoff(Backoff.fixed(10L))
                .leaseMillis(100L)
                .pollWaitMillis(20L)
                .heartbeatEnabled(false)
                .build());
        worker.start("lease-worker-test-failure");
        try {
            String taskId = backend.publish(request("pay", "payload"));
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));

            InMemoryLeaseBackend.StoredTask task = awaitTask(taskId, backend, LeaseTaskStatus.DEAD);
            Assert.assertEquals(3, attempts.get());
            Assert.assertEquals(3, task.getFailureCount());
            Assert.assertEquals(3, task.getDeliveryCount());
            Assert.assertTrue(task.getLastError().contains("boom"));
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testWorkerWithNoSubscriptionsDoesNotConsumeTask() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        LeaseWorker worker = new LeaseWorker(backend, new DefaultLeaseTaskHandlerRegistry(), LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .pollWaitMillis(20L)
                .build());

        worker.start("lease-worker-test-no-subscriptions");
        try {
            String taskId = backend.publish(request("missing", "payload"));
            Thread.sleep(120L);

            InMemoryLeaseBackend.StoredTask task = backend.snapshot().get(taskId);
            Assert.assertNotNull(task);
            Assert.assertEquals(LeaseTaskStatus.SCHEDULED, task.getStatus());
            Assert.assertEquals(0, task.getDeliveryCount());
            Assert.assertEquals(0, task.getFailureCount());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testHandlerReceivesExecutionContextAndCanRequestHeartbeat() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<LeaseExecutionContext> contextRef = new AtomicReference<LeaseExecutionContext>();

        registry.register(DEFAULT_QUEUE, "pay", context -> {
            contextRef.set(context);
            context.requestHeartbeat();
            started.countDown();
            Assert.assertTrue(release.await(2, TimeUnit.SECONDS));
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .leaseMillis(150L)
                .heartbeatIntervalMillis(40L)
                .pollWaitMillis(20L)
                .build());
        worker.start("lease-worker-context");
        String taskId = backend.publish(LeasePublishRequest.builder()
                .queue(DEFAULT_QUEUE)
                .taskType("pay")
                .payload("payload")
                .attribute("traceId", "T-1")
                .build());
        try {
            Assert.assertTrue(started.await(1, TimeUnit.SECONDS));
            LeaseExecutionContext context = contextRef.get();
            Assert.assertNotNull(context);
            Assert.assertEquals(DEFAULT_QUEUE, context.getQueue());
            Assert.assertEquals("pay", context.getTaskType());
            Assert.assertEquals("payload", context.getPayload());
            Assert.assertEquals("T-1", context.getAttributes().get("traceId"));
            Assert.assertEquals(1, context.getDeliveryCount());
            Assert.assertEquals(0, context.getFailureCount());

            Thread.sleep(70L);
            long remainingLeaseMillis = backend.snapshot().get(taskId).getLeaseExpiresAtMillis() - System.currentTimeMillis();
            Assert.assertTrue(remainingLeaseMillis >= 80L);
        } finally {
            release.countDown();
            worker.shutdown();
        }
    }

    @Test
    public void testNonRetryableExceptionDirectlyMarksDead() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch latch = new CountDownLatch(1);

        registry.register(DEFAULT_QUEUE, "pay", context -> {
            latch.countDown();
            throw new NonRetryableLeaseException("poison");
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .maxFailures(10)
                .backoff(Backoff.fixed(10L))
                .pollWaitMillis(20L)
                .heartbeatEnabled(false)
                .build());
        worker.start("lease-worker-non-retryable");
        try {
            String taskId = backend.publish(request("pay", "payload"));
            Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
            InMemoryLeaseBackend.StoredTask task = awaitTask(taskId, backend, LeaseTaskStatus.DEAD);
            Assert.assertEquals(1, task.getFailureCount());
            Assert.assertEquals(1, task.getDeliveryCount());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testShutdownWaitsForInFlightTask() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong shutdownFinishedAt = new AtomicLong(0L);

        registry.register(DEFAULT_QUEUE, "pay", context -> {
            started.countDown();
            Assert.assertTrue(release.await(2, TimeUnit.SECONDS));
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .leaseMillis(200L)
                .heartbeatIntervalMillis(60L)
                .pollWaitMillis(20L)
                .build());
        String taskId = backend.publish(request("pay", "payload"));
        worker.start("lease-worker-shutdown-graceful");
        Assert.assertTrue(started.await(1, TimeUnit.SECONDS));

        Thread shutdownThread = new Thread(() -> {
            worker.shutdown();
            shutdownFinishedAt.set(System.currentTimeMillis());
        });
        shutdownThread.start();

        Thread.sleep(80L);
        Assert.assertTrue(shutdownThread.isAlive());

        release.countDown();
        shutdownThread.join(1_000L);

        Assert.assertTrue(shutdownFinishedAt.get() > 0L);
        Assert.assertEquals(LeaseTaskStatus.SUCCEEDED, awaitTask(taskId, backend, LeaseTaskStatus.SUCCEEDED).getStatus());
    }

    @Test
    public void testWorkerCannotRestartAfterShutdown() {
        LeaseWorker worker = new LeaseWorker(new InMemoryLeaseBackend(),
                new DefaultLeaseTaskHandlerRegistry(),
                LeaseWorkerPolicy.builder().heartbeatEnabled(false).build());
        worker.shutdown();

        try {
            worker.start();
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("restarted"));
        }
    }

    @Test
    public void testMissingHandlerRetryLaterDoesNotIncreaseFailureCount() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry missingRegistry = new DefaultLeaseTaskHandlerRegistry();
        missingRegistry.register(DEFAULT_QUEUE, "known", context -> {
        });

        String taskId = backend.publish(request("missing", "payload"));
        LeaseWorker firstWorker = new LeaseWorker(backend, missingRegistry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .pollWaitMillis(20L)
                .leaseMillis(100L)
                .heartbeatEnabled(false)
                .backoff(Backoff.fixed(500L))
                .missingHandlerStrategy(MissingHandlerStrategy.RETRY_LATER)
                .build());
        firstWorker.start("lease-worker-missing-handler");
        try {
            InMemoryLeaseBackend.StoredTask released = awaitReleasedTask(taskId, backend);
            Assert.assertNotNull(released);
            Assert.assertTrue(released.getDeliveryCount() >= 1);
            Assert.assertEquals(0, released.getFailureCount());
        } finally {
            firstWorker.shutdown();
        }

        DefaultLeaseTaskHandlerRegistry workingRegistry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch latch = new CountDownLatch(1);
        workingRegistry.register(DEFAULT_QUEUE, "missing", context -> latch.countDown());
        LeaseWorker secondWorker = new LeaseWorker(backend, workingRegistry, LeaseWorkerPolicy.builder()
                .workerId("worker-b")
                .pollWaitMillis(20L)
                .leaseMillis(100L)
                .heartbeatEnabled(false)
                .build());
        secondWorker.start("lease-worker-correct-handler");
        try {
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assert.assertEquals(LeaseTaskStatus.SUCCEEDED, awaitTask(taskId, backend, LeaseTaskStatus.SUCCEEDED).getStatus());
        } finally {
            secondWorker.shutdown();
        }
    }

    private InMemoryLeaseBackend.StoredTask awaitTask(String taskId,
                                                      InMemoryLeaseBackend backend,
                                                      LeaseTaskStatus status) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            InMemoryLeaseBackend.StoredTask current = backend.snapshot().get(taskId);
            if (current != null && current.getStatus() == status) {
                return current;
            }
            Thread.sleep(20L);
        }
        return backend.snapshot().get(taskId);
    }

    private InMemoryLeaseBackend.StoredTask awaitReleasedTask(String taskId,
                                                              InMemoryLeaseBackend backend) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            InMemoryLeaseBackend.StoredTask current = backend.snapshot().get(taskId);
            if (current != null
                    && current.getStatus() == LeaseTaskStatus.SCHEDULED
                    && current.getDeliveryCount() == 1
                    && current.getFailureCount() == 0) {
                return current;
            }
            Thread.sleep(20L);
        }
        return backend.snapshot().get(taskId);
    }

    private LeasePublishRequest request(String taskType, String payload) {
        return LeasePublishRequest.builder()
                .queue(DEFAULT_QUEUE)
                .taskType(taskType)
                .payload(payload)
                .build();
    }
}
