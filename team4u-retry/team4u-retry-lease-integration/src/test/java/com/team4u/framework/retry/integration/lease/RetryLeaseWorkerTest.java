package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import com.team4u.framework.retry.exception.RetryHandoffException;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.backend.serialize.HutoolRetryTaskSnapshotSerializer;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RetryLeaseWorkerTest {

    /**
     * 将快照序列化为 Lease 系统的 payload 格式
     */
    private String serializeSnapshot(String taskType, String payload) {
        RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
        snapshot.setTaskType(taskType);
        snapshot.setPayload(payload);
        snapshot.setMaxAttempts(1); // 单次重试
        return HutoolRetryTaskSnapshotSerializer.INSTANCE.serialize(snapshot);
    }

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
            public void recover(RetryTaskSnapshot snapshot) {
                payloadRef.set(snapshot.getPayload());
                latch.countDown();
            }
        });

        RetryLeaseWorker worker = new RetryLeaseWorker(backend, new LeaseRetryBackend(backend), registry,
                LeaseWorkerPolicy.builder()
                        .workerId("retry-lease-worker")
                        .pollWaitMillis(20L)
                        .build());
        worker.start("retry-lease-worker-test");
        try {
            // 使用序列化后的快照作为 payload，与 adapter 的反序列化逻辑一致
            backend.publish(LeasePublishRequest.builder()
                    .queue(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE)
                    .taskType("pay-notify")
                    .payload(serializeSnapshot("pay-notify", "{\"orderId\":\"A1001\"}"))
                    .build());
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assert.assertEquals("{\"orderId\":\"A1001\"}", payloadRef.get());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testRetryLeaseWorkerMarksClosedFailedAfterHandlerFailures() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        final AtomicInteger attempts = new AtomicInteger();

        registry.register(new RecoveryHandler() {
            @Override
            public String key() {
                return "failing-task";
            }

            @Override
            public void recover(RetryTaskSnapshot snapshot) {
                attempts.incrementAndGet();
                throw new IllegalStateException("recover boom");
            }
        });

        RetryLeaseWorker worker = new RetryLeaseWorker(backend, new LeaseRetryBackend(backend), registry,
                LeaseWorkerPolicy.builder()
                        .workerId("retry-lease-worker")
                        .pollWaitMillis(20L)
                        .heartbeatEnabled(false)
                        .build());
        worker.start("retry-lease-worker-fail-test");
        try {
            // 快照 maxAttempts=1，recover 失败后达到上限直接 terminal
            String taskId = backend.publish(LeasePublishRequest.builder()
                    .queue(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE)
                    .taskType("failing-task")
                    .payload(serializeSnapshot("failing-task", "payload"))
                    .build());
            long deadline = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < deadline) {
                if (backend.snapshot().containsKey(taskId)
                        && backend.snapshot().get(taskId).getState() == LeaseTaskState.CLOSED) {
                    break;
                }
                Thread.sleep(20L);
            }

            Assert.assertEquals(1, attempts.get());
            Assert.assertEquals(LeaseTaskOutcome.FAILED, backend.snapshot().get(taskId).getOutcome());
            Assert.assertEquals(LeaseTaskFailureReason.RETRY_EXHAUSTED,
                    backend.snapshot().get(taskId).getFailureReason());
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
            public void recover(RetryTaskSnapshot snapshot) {
                payloadRef.set(snapshot.getPayload());
                latch.countDown();
            }
        });

        try (RetryLeaseWorker worker = new RetryLeaseWorker(leaseBackend, new LeaseRetryBackend(leaseBackend), registry,
                LeaseWorkerPolicy.builder()
                        .workerId("retry-lease-worker-e2e")
                        .pollWaitMillis(20L)
                        .build())) {

            Retryer retryer = Retryer.builder()
                    .policy(RetryPolicy.builder().maxAttempts(3).localAttempts(1).build())
                    .retryBackend(new LeaseRetryBackend(leaseBackend))
                    .build();

            worker.start("retry-lease-worker-e2e-test");

            try {
                retryer.execute("pay-notify", context -> {
                    RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
                    snapshot.setPayload("{\"orderId\":\"A3001\"}");
                    return snapshot;
                }, () -> {
                    callCount.incrementAndGet();
                    throw new IllegalStateException("downstream timeout");
                });
                Assert.fail("应该抛出 RetryHandoffException");
            } catch (RetryHandoffException ex) {
                Assert.assertTrue(ex.getMessage().contains("In-memory retries exhausted"));
            }

            Assert.assertEquals(1, callCount.get());
            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assert.assertEquals("{\"orderId\":\"A3001\"}", payloadRef.get());
        }
    }
}
