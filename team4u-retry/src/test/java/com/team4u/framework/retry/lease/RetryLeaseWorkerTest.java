package com.team4u.framework.retry.lease;

import com.team4u.framework.lease.LeaseWorkerPolicy;
import com.team4u.framework.lease.LeasePublishRequest;
import com.team4u.framework.lease.backoff.Backoff;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.RetryExhaustedException;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RetryLeaseWorkerTest {

    @Test
    public void testRetryLeaseWorkerCanConsumeRecoveryHandler() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> payloadRef = new AtomicReference<String>();

        registry.register(new RecoveryHandler() {
            @Override
            public String key() {
                return "pay-notify";
            }

            @Override
            public void recover(String payload) {
                payloadRef.set(payload);
                latch.countDown();
            }
        });

        RetryLeaseWorker worker = new RetryLeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("retry-lease-worker")
                .pollWaitMillis(20L)
                .build());
        worker.start("retry-lease-worker-test");
        try {
            backend.publish(request("pay-notify", "{\"orderId\":\"A1001\"}"));
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assert.assertEquals("{\"orderId\":\"A1001\"}", payloadRef.get());
            Assert.assertEquals(1, backend.snapshot().size());
            Assert.assertEquals("SUCCEEDED", backend.snapshot().values().iterator().next().getStatus().name());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testRetryLeaseWorkerMarksDeadAfterHandlerFailures() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        final AtomicInteger attempts = new AtomicInteger();

        registry.register(new RecoveryHandler() {
            @Override
            public String key() {
                return "failing-task";
            }

            @Override
            public void recover(String payload) {
                attempts.incrementAndGet();
                throw new IllegalStateException("recover boom");
            }
        });

        RetryLeaseWorker worker = new RetryLeaseWorker(backend, registry, LeaseWorkerPolicy.builder()
                .workerId("retry-lease-worker")
                .maxFailures(2)
                .backoff(Backoff.fixed(10L))
                .pollWaitMillis(20L)
                .heartbeatEnabled(false)
                .build());
        worker.start("retry-lease-worker-fail-test");
        try {
            String taskId = backend.publish(request("failing-task", "payload"));
            long deadline = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < deadline) {
                if (backend.snapshot().containsKey(taskId)
                        && "DEAD".equals(backend.snapshot().get(taskId).getStatus().name())) {
                    break;
                }
                Thread.sleep(20L);
            }

            Assert.assertEquals(2, attempts.get());
            Assert.assertEquals("DEAD", backend.snapshot().get(taskId).getStatus().name());
            Assert.assertEquals(2, backend.snapshot().get(taskId).getFailureCount());
            Assert.assertTrue(backend.snapshot().get(taskId).getLastError().contains("recover boom"));
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testRetryerCanUseLeaseBackendAdapterEndToEnd() throws Exception {
        InMemoryLeaseBackend leaseBackend = new InMemoryLeaseBackend();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> payloadRef = new AtomicReference<String>();
        final AtomicInteger callCount = new AtomicInteger();

        registry.register(new RecoveryHandler() {
            @Override
            public String key() {
                return "pay-notify";
            }

            @Override
            public void recover(String payload) {
                payloadRef.set(payload);
                latch.countDown();
            }
        });

        RetryLeaseWorker worker = new RetryLeaseWorker(leaseBackend, registry, LeaseWorkerPolicy.builder()
                .workerId("retry-lease-worker-e2e")
                .pollWaitMillis(20L)
                .build());

        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder().maxAttempts(3).localAttempts(1).build())
                .backend(leaseBackend)
                .build();

        worker.start("retry-lease-worker-e2e-test");
        try {
            try {
                retryer.execute("pay-notify", context -> "{\"orderId\":\"A3001\"}", () -> {
                    callCount.incrementAndGet();
                    throw new IllegalStateException("downstream timeout");
                });
                Assert.fail("应该抛出 RetryExhaustedException");
            } catch (RetryExhaustedException ex) {
                Assert.assertTrue(ex.getMessage().contains("In-memory retries exhausted"));
            }

            Assert.assertEquals(1, callCount.get());
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assert.assertEquals("{\"orderId\":\"A3001\"}", payloadRef.get());
        } finally {
            worker.shutdown();
        }
    }

    private LeasePublishRequest request(String taskType, String payload) {
        return LeasePublishRequest.builder()
                .queue(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE)
                .taskType(taskType)
                .payload(payload)
                .build();
    }
}
