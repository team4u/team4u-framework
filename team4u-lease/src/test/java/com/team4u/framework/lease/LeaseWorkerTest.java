package com.team4u.framework.lease;

import com.team4u.framework.lease.backoff.Backoff;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    @Test
    public void testHeartbeatUsesLeaseDurationInsteadOfHeartbeatInterval() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        registry.register(new LeaseTaskHandler() {
            @Override
            public String key() {
                return "pay";
            }

            @Override
            public void handle(String payload) throws Exception {
                started.countDown();
                Assert.assertTrue(release.await(2, TimeUnit.SECONDS));
            }
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .leaseMillis(150L)
                .heartbeatIntervalMillis(40L)
                .pollWaitMillis(20L)
                .build());
        worker.start("lease-worker-heartbeat-duration");
        String taskId = backend.publish("pay", "payload");
        try {
            Assert.assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(70L);

            InMemoryLeaseBackend.StoredTask task = snapshotTask(taskId, backend);
            long remainingLeaseMillis = task.getLeaseExpiresAtMillis() - System.currentTimeMillis();
            Assert.assertTrue("remaining lease should still be close to a full lease after heartbeat", remainingLeaseMillis >= 80L);
        } finally {
            release.countDown();
            worker.shutdown();
        }
    }

    @Test
    public void testHeartbeatContinuesAfterTransientBackendFailure() throws Exception {
        FlakyHeartbeatBackend backend = new FlakyHeartbeatBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        final CountDownLatch handled = new CountDownLatch(1);

        registry.register(new LeaseTaskHandler() {
            @Override
            public String key() {
                return "pay";
            }

            @Override
            public void handle(String payload) throws Exception {
                Thread.sleep(220L);
                handled.countDown();
            }
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .leaseMillis(120L)
                .heartbeatIntervalMillis(40L)
                .pollWaitMillis(20L)
                .build());
        worker.start("lease-worker-heartbeat-flaky");
        String taskId = backend.publish("pay", "payload");
        try {
            Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
            InMemoryLeaseBackend.StoredTask task = awaitTask(null, taskId, backend.delegate, LeaseTaskStatus.SUCCEEDED);
            Assert.assertNotNull(task);
            Assert.assertEquals(LeaseTaskStatus.SUCCEEDED, task.getStatus());
            Assert.assertEquals(1, backend.failedHeartbeatCount.get());
            Assert.assertTrue("heartbeat should continue after the transient failure",
                    backend.totalHeartbeatCount.get() > backend.failedHeartbeatCount.get());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testShutdownWaitsForInFlightTask() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicLong shutdownFinishedAt = new AtomicLong(0L);

        registry.register(new LeaseTaskHandler() {
            @Override
            public String key() {
                return "pay";
            }

            @Override
            public void handle(String payload) throws Exception {
                started.countDown();
                Assert.assertTrue(release.await(2, TimeUnit.SECONDS));
            }
        });

        LeaseWorker worker = new LeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("worker-a")
                .leaseMillis(200L)
                .heartbeatIntervalMillis(60L)
                .pollWaitMillis(20L)
                .build());
        String taskId = backend.publish("pay", "payload");
        worker.start("lease-worker-shutdown-graceful");
        Assert.assertTrue(started.await(1, TimeUnit.SECONDS));

        Thread shutdownThread = new Thread(new Runnable() {
            @Override
            public void run() {
                worker.shutdown();
                shutdownFinishedAt.set(System.currentTimeMillis());
            }
        });
        shutdownThread.start();

        Thread.sleep(80L);
        Assert.assertTrue("shutdown should wait until the in-flight task completes", shutdownThread.isAlive());

        release.countDown();
        shutdownThread.join(1_000L);

        Assert.assertTrue("shutdown thread should exit after task completion", shutdownFinishedAt.get() > 0L);
        InMemoryLeaseBackend.StoredTask task = awaitTask(null, taskId, backend, LeaseTaskStatus.SUCCEEDED);
        Assert.assertEquals(LeaseTaskStatus.SUCCEEDED, task.getStatus());
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

    private static final class FlakyHeartbeatBackend implements LeaseBackend {
        private final InMemoryLeaseBackend delegate = new InMemoryLeaseBackend();
        private final AtomicInteger totalHeartbeatCount = new AtomicInteger();
        private final AtomicInteger failedHeartbeatCount = new AtomicInteger();

        @Override
        public String publish(String taskType, String payload) {
            return delegate.publish(taskType, payload);
        }

        @Override
        public String publish(String taskType, String payload, long delayMillis) {
            return delegate.publish(taskType, payload, delayMillis);
        }

        @Override
        public void reschedule(String taskId, long delayMillis) {
            delegate.reschedule(taskId, delayMillis);
        }

        @Override
        public void cancel(String taskId) {
            delegate.cancel(taskId);
        }

        @Override
        public LeaseGrant acquire(String workerId, long leaseMillis, long waitTimeoutMillis) throws InterruptedException {
            return delegate.acquire(workerId, leaseMillis, waitTimeoutMillis);
        }

        @Override
        public void ack(String taskId, String workerId, String leaseToken) {
            delegate.ack(taskId, workerId, leaseToken);
        }

        @Override
        public void retry(String taskId, String workerId, String leaseToken, long delayMillis, Throwable cause) {
            delegate.retry(taskId, workerId, leaseToken, delayMillis, cause);
        }

        @Override
        public void fail(String taskId, String workerId, String leaseToken, Throwable cause) {
            delegate.fail(taskId, workerId, leaseToken, cause);
        }

        @Override
        public void heartbeat(String taskId, String workerId, String leaseToken, long extendMillis) {
            totalHeartbeatCount.incrementAndGet();
            if (failedHeartbeatCount.get() == 0) {
                failedHeartbeatCount.incrementAndGet();
                throw new IllegalStateException("transient heartbeat failure");
            }
            delegate.heartbeat(taskId, workerId, leaseToken, extendMillis);
        }
    }
}
