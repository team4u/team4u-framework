package com.team4u.framework.retry.worker;

import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class RetryWorkerTest {

    private static final long AWAIT_TIMEOUT_SECONDS = 2L;

    @Test
    public void testWorkerConsumesQueuedTask() throws Exception {
        InMemoryRetryBackend backend = new InMemoryRetryBackend();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> payloadRef = new AtomicReference<String>();

        registerHandler(registry, "pay-notify", new RecoveryAction() {
            @Override
            public void recover(String payload) {
                payloadRef.set(payload);
                latch.countDown();
            }
        });

        RetryWorker worker = startWorker(backend, registry, "test-retry-worker");
        try {
            backend.submitForDelay("intent-1", "pay-notify", "{\"orderId\":\"A1001\"}", 0L);
            Assert.assertTrue("worker 应消费到后端任务", latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Assert.assertEquals("{\"orderId\":\"A1001\"}", payloadRef.get());
            Assert.assertTrue("成功恢复后应清理记录", backend.snapshot().isEmpty());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    public void testWorkerMarksTerminalWhenRecoveryFails() throws Exception {
        InMemoryRetryBackend backend = new InMemoryRetryBackend();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        CountDownLatch latch = new CountDownLatch(1);

        registerHandler(registry, "failing-task", new RecoveryAction() {
            @Override
            public void recover(String payload) {
                latch.countDown();
                throw new IllegalStateException("recover boom");
            }
        });

        RetryWorker worker = startWorker(backend, registry, "test-retry-worker-failing");
        try {
            backend.submitForDelay("intent-2", "failing-task", "payload", 0L);
            Assert.assertTrue("worker 应执行失败恢复逻辑", latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            RetryTaskRecord record = awaitRecord("intent-2", backend, RetryTaskRecord.TERMINAL);

            Assert.assertNotNull(record);
            Assert.assertEquals(RetryTaskRecord.TERMINAL, record.getStatus());
            Assert.assertTrue(record.getLastError().contains("recover boom"));
        } finally {
            worker.shutdown();
        }
    }

    private RetryWorker startWorker(InMemoryRetryBackend backend, RecoveryHandlerRegistry registry, String threadName) {
        RetryWorker worker = new RetryWorker(backend, registry);
        worker.start(threadName);
        return worker;
    }

    private void registerHandler(RecoveryHandlerRegistry registry, final String taskType, final RecoveryAction action) {
        registry.register(new RecoveryHandler() {
            @Override
            public String key() {
                return taskType;
            }

            @Override
            public void recover(String payload) {
                action.recover(payload);
            }
        });
    }

    private RetryTaskRecord awaitRecord(String intentId, InMemoryRetryBackend backend, String expectedStatus)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            RetryTaskRecord record = backend.snapshot().get(intentId);
            if (record != null && expectedStatus.equals(record.getStatus())) {
                return record;
            }
            Thread.sleep(20L);
        }
        return backend.snapshot().get(intentId);
    }

    private interface RecoveryAction {
        void recover(String payload);
    }
}
