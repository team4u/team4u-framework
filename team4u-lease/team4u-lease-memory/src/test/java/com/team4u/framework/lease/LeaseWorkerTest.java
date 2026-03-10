package com.team4u.framework.lease;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.enums.*;
import com.team4u.framework.lease.handler.DefaultLeaseTaskHandlerRegistry;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.model.*;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
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
            String taskId = backend.publish(request("pay", "{\"id\":1}"));
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assert.assertEquals("{\"id\":1}", payloadRef.get());
            Assert.assertEquals(LeaseTaskOutcome.SUCCEEDED,
                    awaitTask(taskId, backend, LeaseTaskState.CLOSED).getOutcome());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testWorkerMarksDeadOnFailure() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();

        registry.register(DEFAULT_QUEUE, "pay", context -> {
            attempts.incrementAndGet();
            latch.countDown();
            throw new IllegalStateException("boom");
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .pollWaitMillis(20L)
                .heartbeatEnabled(false)
                .build());
        worker.start("lease-worker-test-failure");
        try {
            String taskId = backend.publish(request("pay", "payload"));
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));

            InMemoryLeaseBackend.StoredTask task = awaitTask(taskId, backend, LeaseTaskState.CLOSED);
            Assert.assertEquals(1, attempts.get());
            Assert.assertEquals(1, task.getFailureCount());
            Assert.assertEquals(1, task.getDeliveryCount());
            Assert.assertEquals(LeaseTaskOutcome.FAILED, task.getOutcome());
            Assert.assertEquals(LeaseTaskFailureReason.HANDLER_EXCEPTION, task.getFailureReason());
            Assert.assertTrue(task.getErrorMessage().contains("boom"));
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
            Assert.assertEquals(LeaseTaskState.READY, task.getState());
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
            long remainingLeaseMillis = backend.snapshot().get(taskId).getLeaseExpiresAtMillis()
                    - System.currentTimeMillis();
            Assert.assertTrue(remainingLeaseMillis >= 80L);
        } finally {
            release.countDown();
            worker.shutdown();
        }
    }

    @Test
    public void testExceptionDirectlyClosesFailed() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch latch = new CountDownLatch(1);

        registry.register(DEFAULT_QUEUE, "pay", context -> {
            latch.countDown();
            throw new RuntimeException("poison");
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .pollWaitMillis(20L)
                .heartbeatEnabled(false)
                .build());
        worker.start("lease-worker-non-retryable");
        try {
            String taskId = backend.publish(request("pay", "payload"));
            Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
            InMemoryLeaseBackend.StoredTask task = awaitTask(taskId, backend, LeaseTaskState.CLOSED);
            Assert.assertEquals(1, task.getFailureCount());
            Assert.assertEquals(1, task.getDeliveryCount());
            Assert.assertEquals(LeaseTaskOutcome.FAILED, task.getOutcome());
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
        Assert.assertEquals(LeaseTaskOutcome.SUCCEEDED,
                awaitTask(taskId, backend, LeaseTaskState.CLOSED).getOutcome());
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
    public void testRequestHeartbeatDoesNotRunConcurrentlyWithScheduledHeartbeat() throws Exception {
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch finishHandler = new CountDownLatch(1);
        ControlledRuntimeClient runtimeClient = new ControlledRuntimeClient();

        registry.register(DEFAULT_QUEUE, "pay", context -> {
            handlerStarted.countDown();
            context.requestHeartbeat();
            context.requestHeartbeat();
            Assert.assertTrue(finishHandler.await(2, TimeUnit.SECONDS));
        });

        LeaseWorker worker = new LeaseWorker(runtimeClient, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .leaseMillis(200L)
                .heartbeatIntervalMillis(20L)
                .pollWaitMillis(20L)
                .build());
        worker.start("lease-worker-heartbeat-serialized");
        try {
            Assert.assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));
            Assert.assertTrue(runtimeClient.awaitHeartbeatStarted());
            Thread.sleep(80L);

            Assert.assertEquals(1, runtimeClient.getMaxConcurrentHeartbeats());
            Assert.assertEquals(1, runtimeClient.getHeartbeatCalls());
        } finally {
            finishHandler.countDown();
            worker.shutdownGracefully(1_000L);
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
            Assert.assertEquals(LeaseTaskOutcome.SUCCEEDED,
                    awaitTask(taskId, backend, LeaseTaskState.CLOSED).getOutcome());
        } finally {
            secondWorker.shutdown();
        }
    }

    private InMemoryLeaseBackend.StoredTask awaitTask(String taskId,
                                                      InMemoryLeaseBackend backend,
                                                      LeaseTaskState state) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            InMemoryLeaseBackend.StoredTask current = backend.snapshot().get(taskId);
            if (current != null && current.getState() == state) {
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
                    && current.getState() == LeaseTaskState.READY
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

    private static final class ControlledRuntimeClient implements LeaseRuntimeClient {
        private final LeaseGrant grant = LeaseGrant.builder()
                .taskId("task-1")
                .workerId("worker-a")
                .leaseToken("lease-1")
                .queue(DEFAULT_QUEUE)
                .taskType("pay")
                .payload("payload")
                .deliveryCount(1)
                .failureCount(0)
                .leaseExpiresAtMillis(System.currentTimeMillis() + 1_000L)
                .build();
        private final CountDownLatch heartbeatStarted = new CountDownLatch(1);
        private final AtomicInteger activeHeartbeats = new AtomicInteger();
        private final AtomicInteger maxConcurrentHeartbeats = new AtomicInteger();
        private final AtomicInteger heartbeatCalls = new AtomicInteger();

        @Override
        public LeaseGrant acquire(LeaseAcquireRequest request) {
            return grant;
        }

        @Override
        public LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
            int current = activeHeartbeats.incrementAndGet();
            heartbeatCalls.incrementAndGet();
            updateMaxConcurrent(current);
            heartbeatStarted.countDown();
            try {
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                activeHeartbeats.decrementAndGet();
            }
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request) {
            return LeaseRuntimeResult.APPLIED;
        }

        private boolean awaitHeartbeatStarted() throws InterruptedException {
            return heartbeatStarted.await(1, TimeUnit.SECONDS);
        }

        private int getMaxConcurrentHeartbeats() {
            return maxConcurrentHeartbeats.get();
        }

        private int getHeartbeatCalls() {
            return heartbeatCalls.get();
        }

        private void updateMaxConcurrent(int current) {
            while (true) {
                int existing = maxConcurrentHeartbeats.get();
                if (current <= existing) {
                    return;
                }
                if (maxConcurrentHeartbeats.compareAndSet(existing, current)) {
                    return;
                }
            }
        }
    }
}
