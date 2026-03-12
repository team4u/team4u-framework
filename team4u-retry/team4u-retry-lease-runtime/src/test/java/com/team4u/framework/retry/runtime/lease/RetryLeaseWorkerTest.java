package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.model.*;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryLeaseWorkerTest {

    private static boolean awaitAcquire(TrackingRuntimeClient runtimeClient) {
        try {
            return runtimeClient.awaitAcquire(1_000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

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

    /**
     * 验证完整构造函数会把显式传入的 registry/policy 装配到内部委托对象。
     */
    @Test
    public void testConstructorUsesProvidedRegistryAndPolicy() throws Exception {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        LeaseWorkerPolicy policy = LeaseWorkerPolicy.builder()
                .workerId("retry-worker")
                .pollWaitMillis(10L)
                .heartbeatEnabled(false)
                .build();

        RetryLeaseWorker worker = new RetryLeaseWorker(runtimeClient, registry, policy);

        Assert.assertSame(registry, getField(worker, "registry"));

        LeaseWorker delegate = (LeaseWorker) getField(worker, "delegate");
        Assert.assertSame(policy, getField(delegate, "policy"));

        RecoveryHandlerRegistryLeaseAdapter adapter =
                (RecoveryHandlerRegistryLeaseAdapter) getField(delegate, "registry");
        Assert.assertSame(registry, getField(adapter, "delegate"));
        Assert.assertEquals(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE, getField(adapter, "queue"));
    }

    /**
     * 验证便捷构造函数会回落到全局 registry 和默认恢复队列。
     */
    @Test
    public void testDefaultConstructorsUseGlobalRegistryAndDefaultQueue() throws Exception {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();

        RetryLeaseWorker worker = new RetryLeaseWorker(runtimeClient);

        Assert.assertSame(RecoveryHandlerRegistry.global(), getField(worker, "registry"));

        LeaseWorker delegate = (LeaseWorker) getField(worker, "delegate");
        LeaseWorkerPolicy policy = (LeaseWorkerPolicy) getField(delegate, "policy");
        Assert.assertNotNull(policy);

        RecoveryHandlerRegistryLeaseAdapter adapter =
                (RecoveryHandlerRegistryLeaseAdapter) getField(delegate, "registry");
        Assert.assertSame(RecoveryHandlerRegistry.global(), getField(adapter, "delegate"));
        Assert.assertEquals(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE, getField(adapter, "queue"));
    }

    /**
     * 验证 register 会直接委托给底层 registry。
     */
    @Test
    public void testRegisterDelegatesToRegistry() {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        RetryLeaseWorker worker = new RetryLeaseWorker(runtimeClient, registry);
        TestStringRecoveryHandler handler = new TestStringRecoveryHandler("payment");

        worker.register(handler);

        Optional<?> registered = registry.get("payment");
        Assert.assertTrue(registered.isPresent());
        Assert.assertSame(handler, registered.get());
    }

    /**
     * 验证带线程名的启动和优雅关闭会正确转发到内部 LeaseWorker。
     */
    @Test
    public void testStartWithThreadNameAndShutdownGracefully() throws Exception {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        RetryLeaseWorker worker = new RetryLeaseWorker(
                runtimeClient,
                new RecoveryHandlerRegistry(),
                LeaseWorkerPolicy.builder().pollWaitMillis(10L).heartbeatEnabled(false).build());

        worker.start("retry-lease-worker-test");
        Assert.assertTrue(runtimeClient.awaitAcquire(1_000L));

        LeaseWorker delegate = (LeaseWorker) getField(worker, "delegate");
        Thread workerThread = (Thread) getField(delegate, "workerThread");
        Assert.assertNotNull(workerThread);
        Assert.assertEquals("retry-lease-worker-test", workerThread.getName());

        Assert.assertTrue(worker.shutdownGracefully(1_000L));
    }

    /**
     * 验证默认启动路径会沿用 LeaseWorker 的默认线程名。
     */
    @Test
    public void testStartUsesDefaultThreadNameAndShutdown() throws Exception {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        RetryLeaseWorker worker = new RetryLeaseWorker(
                runtimeClient,
                new RecoveryHandlerRegistry(),
                LeaseWorkerPolicy.builder().pollWaitMillis(10L).heartbeatEnabled(false).build());

        worker.start();
        Assert.assertTrue(runtimeClient.awaitAcquire(1_000L));

        LeaseWorker delegate = (LeaseWorker) getField(worker, "delegate");
        Thread workerThread = (Thread) getField(delegate, "workerThread");
        Assert.assertNotNull(workerThread);
        Assert.assertEquals("lease-worker", workerThread.getName());

        worker.shutdown();
    }

    /**
     * 验证 run、shutdownNow 和 close 等方法可安全委托到底层实现。
     */
    @Test
    public void testRunShutdownNowAndCloseDelegateToLeaseWorker() {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        RetryLeaseWorker worker = new RetryLeaseWorker(
                runtimeClient,
                new RecoveryHandlerRegistry(),
                LeaseWorkerPolicy.builder().pollWaitMillis(10L).heartbeatEnabled(false).build());

        worker.run();
        Assert.assertEquals(0, runtimeClient.getAcquireCalls());

        worker.start("retry-worker-now");
        Assert.assertTrue(awaitAcquire(runtimeClient));
        worker.shutdownNow();

        worker.close();
    }

    /**
     * 仅用于验证注册链路，不执行真实恢复逻辑。
     */
    private static class TestStringRecoveryHandler implements StringRecoveryHandler {

        private final String taskName;

        private TestStringRecoveryHandler(String taskName) {
            this.taskName = taskName;
        }

        @Override
        public String taskName() {
            return taskName;
        }

        @Override
        public void recover(String payload, RecoveryContext context) {
        }
    }

    /**
     * 以最小运行时客户端实现观察 worker 是否进入 acquire 循环，并通过中断退出。
     */
    private static class TrackingRuntimeClient implements LeaseRuntimeClient {

        private final CountDownLatch acquireLatch = new CountDownLatch(1);
        private final AtomicInteger acquireCalls = new AtomicInteger();

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

        private boolean awaitAcquire(long timeoutMillis) throws InterruptedException {
            return acquireLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private int getAcquireCalls() {
            return acquireCalls.get();
        }
    }
}
