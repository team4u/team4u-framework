package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.model.*;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import com.team4u.framework.retry.TestLeaseBackend;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ManagedRetryRuntimesTest {

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    @Test
    public void testBuildUsesDefaults() {
        NoopLeaseBackend backend = new NoopLeaseBackend();

        ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(backend).build();

        Assert.assertNotNull(runtime.client());
        Assert.assertNotNull(runtime.worker());
        Assert.assertSame(RecoveryHandlerRegistry.global(), runtime.registry());

        runtime.close();
    }

    @Test
    public void testAutoScanFlagControlsAdditionalScanInvocation() {
        NoopLeaseBackend backend = new NoopLeaseBackend();
        CountingRegistry disabledRegistry = new CountingRegistry();
        CountingRegistry enabledRegistry = new CountingRegistry();

        ManagedRetryRuntime disabledRuntime = ManagedRetryRuntime.lease(backend)
                .registry(disabledRegistry)
                .autoScanRecoveryHandlers(false)
                .build();
        ManagedRetryRuntime enabledRuntime = ManagedRetryRuntime.lease(backend)
                .registry(enabledRegistry)
                .autoScanRecoveryHandlers(true)
                .build();

        Assert.assertEquals(1, disabledRegistry.autoScanCalls());
        Assert.assertEquals(2, enabledRegistry.autoScanCalls());

        disabledRuntime.close();
        enabledRuntime.close();
    }

    @Test
    public void testStartUsesConfiguredThreadName() throws Exception {
        TrackingLeaseBackend backend = new TrackingLeaseBackend();

        ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(backend)
                .registry(new RecoveryHandlerRegistry())
                .workerPolicy(LeaseWorkerPolicy.builder()
                        .pollWaitMillis(10L)
                        .heartbeatEnabled(false)
                        .build())
                .workerThreadName("managed-runtime-worker")
                .start();

        Assert.assertTrue(backend.awaitAcquire(1_000L));

        LeaseWorker delegate = (LeaseWorker) getField(runtime.worker(), "delegate");
        Thread workerThread = (Thread) getField(delegate, "workerThread");
        Assert.assertEquals("managed-runtime-worker", workerThread.getName());

        runtime.shutdown();
    }

    @Test
    public void testBuilderAppliesDefaultPolicyToManagedClient() {
        NoopLeaseBackend backend = new NoopLeaseBackend();
        RetryPolicy defaultPolicy = RetryPolicy.builder()
                .maxRetries(3)
                .foregroundMaxAttempts(2)
                .backoff(Backoffs.fixed(0L))
                .build();

        ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(backend)
                .defaultPolicy(defaultPolicy)
                .build();

        Assert.assertNotNull(runtime.client());
        runtime.close();
    }

    private static class CountingRegistry extends RecoveryHandlerRegistry {
        private int autoScanCalls;

        @Override
        public synchronized void autoScan() {
            autoScanCalls++;
        }

        private int autoScanCalls() {
            return autoScanCalls;
        }
    }

    private static class NoopLeaseBackend extends TestLeaseBackend {
        @Override
        public String saveIntent(String taskType, String payload) {
            return "task-1";
        }

        @Override
        public void completeIntent(String intentId) {
        }

        @Override
        public void submitForDelay(String intentId, String taskType, String payload, long delay) {
        }

        @Override
        public LeaseAdminResult update(LeaseUpdateRequest request) {
            return LeaseAdminResult.APPLIED;
        }
    }

    private static class TrackingLeaseBackend implements LeaseBackend {
        private final CountDownLatch acquireLatch = new CountDownLatch(1);
        private final AtomicInteger acquireCalls = new AtomicInteger();

        private boolean awaitAcquire(long timeoutMillis) throws InterruptedException {
            return acquireLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public String publish(LeasePublishRequest request) {
            return "task-1";
        }

        @Override
        public LeaseAdminResult reschedule(String taskId, long delayMillis) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult requeueFailed(String taskId, long delayMillis) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult update(LeaseUpdateRequest request) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseGrant acquire(LeaseAcquireRequest request) throws InterruptedException {
            acquireCalls.incrementAndGet();
            acquireLatch.countDown();
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(10L);
            }
            throw new InterruptedException("interrupted");
        }

        @Override
        public LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request) {
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public Optional<LeaseTaskRecord> get(String taskId) {
            return Optional.empty();
        }

        @Override
        public LeaseTaskPage list(LeaseQueryRequest request) {
            return LeaseTaskPage.builder().total(0).page(0).pageSize(0).build();
        }
    }
}
