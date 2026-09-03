package com.team4u.framework.lease.runtime;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.TaskContext;
import com.team4u.framework.lease.api.TaskHandler;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.RetryCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import com.team4u.framework.lease.spi.UpdateCommand;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
public class TaskWorkerTest {

    @Test
    public void testSuccessClosesSucceeded() throws Exception {
        assertWriteBack(TaskResult.success(), CloseKind.SUCCEEDED, null);
    }

    @Test
    public void testFailureClosesFailed() throws Exception {
        assertWriteBack(TaskResult.failure("bad input"), CloseKind.FAILED, "bad input");
    }

    @Test
    public void testCancelClosesCancelled() throws Exception {
        assertWriteBack(TaskResult.cancel(), CloseKind.CANCELLED, null);
    }

    @Test
    public void testRetryReleasesPending() throws Exception {
        FakeBackend backend = new FakeBackend();
        CountDownLatch handled = new CountDownLatch(1);
        TaskWorker worker = worker(backend, new CountDownHandler(
                TaskResult.retryAfter(Duration.ofMillis(50)), handled));

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        backend.awaitRelease();

        Assert.assertEquals(50L, backend.lastRetry.getDelayMillis());
        Assert.assertEquals(0, backend.closeCalls);
        worker.shutdownNow();
    }

    @Test
    public void testHandlerExceptionClosesFailed() throws Exception {
        FakeBackend backend = new FakeBackend();
        CountDownLatch handled = new CountDownLatch(1);
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) throws Exception {
                handled.countDown();
                throw new IllegalStateException("boom");
            }
        };
        TaskWorker worker = worker(backend, handler);

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        backend.awaitClose();

        Assert.assertEquals(TaskStatus.FAILED, backend.lastCompletion.getStatus());
        Assert.assertEquals("java.lang.IllegalStateException: boom",
                backend.lastCompletion.getErrorMessage());
        worker.shutdownNow();
    }

    @Test(timeout = 2000)
    public void testInfrastructureExceptionAbandonsLeaseWithoutWriteBack() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.singleAcquire = true;
        CountDownLatch handled = new CountDownLatch(1);
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) {
                handled.countDown();
                throw new TaskInfrastructureException("serialization failed",
                        new IllegalStateException("codec down"));
            }
        };
        TaskWorker worker = Leases.queue(backend, "orders").worker()
                .handle("email.send", handler)
                .workerId("worker-1")
                .lease(Duration.ofMillis(200))
                .pollInterval(Duration.ofMillis(10))
                .heartbeatEnabled(true)
                .heartbeatInterval(Duration.ofMillis(80))
                .threadName("worker-infrastructure")
                .build();

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        // 等确定性的终态而非固定 sleep：优雅关退化等 worker 线程退出后再断言计数。
        Assert.assertTrue(worker.shutdownGracefully(Duration.ofSeconds(1)));

        Assert.assertEquals(1, backend.acquireCalls);
        Assert.assertEquals(0, backend.closeCalls);
        Assert.assertEquals(0, backend.releaseCalls);
        Assert.assertEquals(0, backend.heartbeatCalls);
    }

    @Test
    public void testCloseWriteBackFailureIsNotReclosedAsFailed() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.closeFailure = new IllegalStateException("write backend down");
        CountDownLatch handled = new CountDownLatch(1);
        TaskWorker worker = worker(backend, new CountDownHandler(TaskResult.success(), handled));

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        backend.awaitClose();
        assertNoSecondWriteBack(backend);

        Assert.assertEquals(1, backend.closeCalls);
        Assert.assertEquals(TaskStatus.SUCCEEDED, backend.lastCompletion.getStatus());
        worker.shutdownNow();
    }

    @Test
    public void testHandlerFailureAndCloseWriteBackFailureDoesNotCloseAgain() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.closeFailure = new IllegalStateException("write backend down");
        CountDownLatch handled = new CountDownLatch(1);
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) throws Exception {
                handled.countDown();
                throw new IllegalStateException("boom");
            }
        };
        TaskWorker worker = worker(backend, handler);

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        backend.awaitClose();
        assertNoSecondWriteBack(backend);

        Assert.assertEquals(1, backend.closeCalls);
        Assert.assertEquals(TaskStatus.FAILED, backend.lastCompletion.getStatus());
        worker.shutdownNow();
    }

    @Test
    public void testNullHandlerResultClosesFailed() throws Exception {
        FakeBackend backend = new FakeBackend();
        CountDownLatch handled = new CountDownLatch(1);
        TaskWorker worker = worker(backend, new CountDownHandler(null, handled));

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        backend.awaitClose();

        Assert.assertEquals(1, backend.closeCalls);
        Assert.assertEquals(TaskStatus.FAILED, backend.lastCompletion.getStatus());
        worker.shutdownNow();
    }

    @Test
    public void testReleaseWriteBackFailureDoesNotCloseFailed() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.releaseFailure = new IllegalStateException("write backend down");
        CountDownLatch handled = new CountDownLatch(1);
        TaskWorker worker = worker(backend, new CountDownHandler(
                TaskResult.retryAfter(Duration.ZERO), handled));

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        backend.awaitRelease();
        assertNoSecondWriteBack(backend);

        Assert.assertEquals(0, backend.closeCalls);
        worker.shutdownNow();
    }

    @Test
    public void testZeroConfigurationGeneratesUniqueWorkerIds() throws Exception {
        FakeBackend firstBackend = new FakeBackend();
        FakeBackend secondBackend = new FakeBackend();
        CountDownLatch acquired = new CountDownLatch(2);
        firstBackend.onAcquire = acquired;
        secondBackend.onAcquire = acquired;
        TaskQueue firstQueue = Leases.queue(firstBackend, "orders");
        TaskQueue secondQueue = Leases.queue(secondBackend, "orders");

        TaskWorker first = firstQueue.worker()
                .handle("email.send", new WaitingHandler(TaskResult.success()))
                .threadName("worker-zero-1")
                .build()
                .start();
        TaskWorker second = secondQueue.worker()
                .handle("email.send", new WaitingHandler(TaskResult.success()))
                .threadName("worker-zero-2")
                .build()
                .start();

        Assert.assertTrue(acquired.await(1, TimeUnit.SECONDS));
        Assert.assertNotNull(firstBackend.lastAcquire);
        Assert.assertNotNull(secondBackend.lastAcquire);
        Assert.assertNotEquals(firstBackend.lastAcquire.getWorkerId(),
                secondBackend.lastAcquire.getWorkerId());
        first.shutdownNow();
        second.shutdownNow();
    }

    @Test
    public void testAcquireUsesTypedSubscription() throws Exception {
        FakeBackend backend = new FakeBackend();
        CountDownLatch acquired = new CountDownLatch(1);
        backend.onAcquire = acquired;
        TaskQueue queue = Leases.queue(backend, "orders");
        TaskWorker worker = queue.worker()
                .handle("email.send", new WaitingHandler(TaskResult.success()))
                .handle("report.build", new WaitingHandler(TaskResult.success()))
                .workerId("worker-1")
                .lease(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(10))
                .heartbeatEnabled(false)
                .threadName("worker-subscription")
                .build();

        worker.start();
        Assert.assertTrue(acquired.await(1, TimeUnit.SECONDS));

        Assert.assertEquals("orders", backend.lastAcquire.getSubscription().getQueue());
        Set<String> types = backend.lastAcquire.getSubscription().getTaskTypes();
        Assert.assertEquals(2, types.size());
        Assert.assertTrue(types.contains("email.send"));
        Assert.assertTrue(types.contains("report.build"));
        worker.shutdownNow();
    }

    @Test
    public void testSubscriptionAccessorIsNotPublicApi() {
        for (Method method : TaskWorker.class.getMethods()) {
            Assert.assertNotEquals("subscription", method.getName());
        }
    }

    @Test
    public void testHeartbeatExtendsLease() throws Exception {
        FakeBackend backend = new FakeBackend();
        CountDownLatch heartbeat = new CountDownLatch(2);
        backend.onHeartbeat = heartbeat;
        CountDownLatch finish = new CountDownLatch(1);
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) throws Exception {
                heartbeat.await(1, TimeUnit.SECONDS);
                finish.countDown();
                return TaskResult.success();
            }
        };
        TaskWorker worker = heartbeatWorker(backend, handler);

        worker.start();
        Assert.assertTrue(finish.await(1, TimeUnit.SECONDS));
        backend.awaitClose();
        Assert.assertTrue(heartbeat.getCount() == 0);
        Assert.assertEquals(1000L, backend.lastHeartbeatMillis);
        worker.shutdownNow();
    }
    @Test(timeout = 2000)
    public void testHeartbeatScheduleRejectionAbandonsExecutionWithoutHandlerOrClose() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.acquireStarted = new CountDownLatch(1);
        backend.acquireGranted = new CountDownLatch(1);
        backend.acquireBlocked = true;
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) {
                backend.handlerInvocations++;
                return TaskResult.success();
            }
        };
        TaskWorker worker = Leases.queue(backend, "orders").worker()
                .handle("email.send", handler)
                .workerId("worker-1")
                .lease(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(10))
                .heartbeatEnabled(true)
                .threadName("worker-schedule-rejection")
                .build();

        worker.start();
        Assert.assertTrue(backend.acquireGranted.await(1, TimeUnit.SECONDS));
        worker.shutdownNow();
        backend.acquireRelease = true;
        assertThreadTerminated("worker-schedule-rejection");
        Assert.assertEquals(0, backend.handlerInvocations);
        Assert.assertEquals(0, backend.closeCalls);
    }

    @Test(timeout = 2000)
    public void testHeartbeatContinuesDuringSlowCloseWriteBack() throws Exception {
        CountDownLatch heartbeat = new CountDownLatch(1);
        FakeBackend backend = new FakeBackend();
        backend.onHeartbeat = heartbeat;
        backend.closeGateOpen = false;
        backend.closeStarted = new CountDownLatch(1);
        TaskWorker worker = heartbeatWorker(backend, handlerAwaitingHeartbeat(heartbeat));

        worker.start();
        Assert.assertTrue(backend.awaitCloseStarted(1, TimeUnit.SECONDS));
        Assert.assertTrue(backend.awaitHeartbeatCount(2, 1, TimeUnit.SECONDS));

        backend.closeGateOpen = true;
        backend.awaitClose();
        worker.shutdownNow();
    }

    @Test(timeout = 2000)
    public void testHeartbeatContinuesDuringSlowReleaseWriteBack() throws Exception {
        CountDownLatch heartbeat = new CountDownLatch(1);
        FakeBackend backend = new FakeBackend();
        backend.onHeartbeat = heartbeat;
        backend.releaseGateOpen = false;
        backend.releaseStarted = new CountDownLatch(1);
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) throws Exception {
                Assert.assertTrue(heartbeat.await(1, TimeUnit.SECONDS));
                return TaskResult.retryAfter(Duration.ZERO);
            }
        };
        TaskWorker worker = heartbeatWorker(backend, handler);

        worker.start();
        Assert.assertTrue(backend.awaitReleaseStarted(1, TimeUnit.SECONDS));
        Assert.assertTrue(backend.awaitHeartbeatCount(2, 1, TimeUnit.SECONDS));

        backend.releaseGateOpen = true;
        backend.awaitRelease();
        worker.shutdownNow();
    }

    @Test(timeout = 2000)
    public void testHeartbeatStopsAfterLeaseLost() throws Exception {
        assertHeartbeatStopsAfterDefinitiveResult(RuntimeResult.LEASE_LOST);
    }

    @Test(timeout = 2000)
    public void testHeartbeatStopsAfterTaskNotFound() throws Exception {
        assertHeartbeatStopsAfterDefinitiveResult(RuntimeResult.TASK_NOT_FOUND);
    }

    @Test(timeout = 2000)
    public void testHeartbeatStopsAfterTerminal() throws Exception {
        assertHeartbeatStopsAfterDefinitiveResult(RuntimeResult.TERMINAL);
    }

    private void assertHeartbeatStopsAfterDefinitiveResult(final RuntimeResult result)
            throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.heartbeatResults = new RuntimeResult[] { result, RuntimeResult.APPLIED };
        CountDownLatch heartbeat = new CountDownLatch(1);
        backend.onHeartbeat = heartbeat;
        CountDownLatch finish = new CountDownLatch(1);
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) throws Exception {
                heartbeat.await(1, TimeUnit.SECONDS);
                finish.await(2, TimeUnit.SECONDS);
                return TaskResult.success();
            }
        };
        TaskWorker worker = heartbeatWorker(backend, handler);

        worker.start();
        Assert.assertTrue(heartbeat.await(1, TimeUnit.SECONDS));
        // 否定性窗口：间隔 20ms 下 60ms 已覆盖 3 个周期，足够暴露未停的调度
        Thread.sleep(60L);
        Assert.assertEquals(1, backend.heartbeatCalls);

        finish.countDown();
        backend.awaitClose();
        worker.shutdownNow();
    }

    @Test(timeout = 2000)
    public void testHeartbeatContinuesAfterTransportException() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.heartbeatFailuresBeforeSuccess = 2;
        CountDownLatch secondFailure = new CountDownLatch(1);
        backend.onHeartbeatFailure = secondFailure;
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) throws Exception {
                secondFailure.await(1, TimeUnit.SECONDS);
                backend.awaitHeartbeatCount(3, 2, TimeUnit.SECONDS);
                return TaskResult.success();
            }
        };
        TaskWorker worker = heartbeatWorker(backend, handler);

        worker.start();
        Assert.assertTrue(secondFailure.await(1, TimeUnit.SECONDS));
        backend.awaitClose();
        worker.shutdownNow();
        Assert.assertEquals(3, backend.heartbeatCalls);
        Assert.assertEquals(RuntimeResult.APPLIED, backend.lastHeartbeatResult);
    }

    @Test
    public void testGracefulShutdownWaitsForHandler() throws Exception {
        FakeBackend backend = new FakeBackend();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) throws Exception {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                return TaskResult.success();
            }
        };
        TaskWorker worker = queueWorker(backend, handler, false);
        worker.start();

        Assert.assertTrue(entered.await(1, TimeUnit.SECONDS));
        Assert.assertFalse(worker.shutdownGracefully(Duration.ofMillis(100)));
        release.countDown();
        Assert.assertTrue(worker.shutdownGracefully(Duration.ofSeconds(2)));
    }
    @Test(timeout = 2000)
    public void testGracefulShutdownDoesNotInterruptFinalCloseWriteBack() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.closeGateOpen = false;
        backend.closeStarted = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(1);
        TaskWorker worker = worker(backend, new CountDownHandler(TaskResult.success(), handled));

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(backend.awaitCloseStarted(1, TimeUnit.SECONDS));

        Assert.assertFalse(worker.shutdownGracefully(Duration.ofMillis(200)));
        Assert.assertEquals(0, backend.closeInterrupted.get());

        backend.closeGateOpen = true;
        backend.awaitClose();
        Assert.assertEquals(RuntimeResult.APPLIED, backend.lastCloseResult);
        Assert.assertTrue(worker.shutdownGracefully(Duration.ofSeconds(2)));
    }
    @Test(timeout = 1000)
    public void testZeroGraceTimeoutChecksImmediately() throws Exception {
        FakeBackend backend = new FakeBackend();
        BlockedHandler handler = BlockedHandler.start();
        TaskWorker worker = queueWorker(backend, handler, false);
        worker.start();
        Assert.assertTrue(handler.entered.await(1, TimeUnit.SECONDS));

        long start = System.nanoTime();
        Assert.assertFalse(worker.shutdownGracefully(Duration.ZERO));
        Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500L);

        handler.release.countDown();
        Assert.assertTrue(worker.shutdownGracefully(Duration.ofSeconds(2)));
    }

    @Test(timeout = 2000)
    public void testDefaultShutdownFinalizesWorkerAfterTimeout() throws Exception {
        FakeBackend backend = new FakeBackend();
        BlockedHandler handler = BlockedHandler.start();
        TaskWorker worker = Leases.queue(backend, "orders").worker()
                .handle("email.send", handler)
                .workerId("worker-1")
                .lease(Duration.ofMillis(20))
                .pollInterval(Duration.ofMillis(10))
                .heartbeatEnabled(false)
                .threadName("worker-default-shutdown")
                .build();
        worker.start();
        Assert.assertTrue(handler.entered.await(1, TimeUnit.SECONDS));

        long start = System.nanoTime();
        worker.shutdown();
        Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500L);
        assertThreadTerminated("worker-default-shutdown");
    }

    @Test
    public void testRejectsMissingHandlerAndInvalidConfiguration() {
        FakeBackend backend = new FakeBackend();
        TaskQueue queue = Leases.queue(backend, "orders");

        assertBuildFails(queue.worker());
        assertBuildFails(queue.worker().handle("email.send", new WaitingHandler(TaskResult.success()))
                .workerId("worker-1").lease(Duration.ofMillis(1)));
        assertBuildFails(queue.worker().handle("email.send", new WaitingHandler(TaskResult.success()))
                .workerId("worker-1").lease(Duration.ofSeconds(1))
                .heartbeatInterval(Duration.ofSeconds(1)));
        assertBuildFails(queue.worker().handle("email.send", new WaitingHandler(TaskResult.success()))
                .workerId("worker-1").lease(Duration.ofSeconds(1))
                .heartbeatEnabled(true).heartbeatInterval(Duration.ofSeconds(2)));
        try {
            queue.worker().handle("email.send", new WaitingHandler(TaskResult.success()))
                    .workerId("worker-1").lease(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(0, 1000));
            Assert.fail("expected sub-millisecond pollInterval to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertDurationOverflowRejected(queue);
    }

    private void assertDurationOverflowRejected(TaskQueue queue) {
        TaskWorker.Builder builder = queue.worker()
                .handle("email.send", new WaitingHandler(TaskResult.success()))
                .workerId("worker-1");
        try {
            builder.lease(Duration.ofSeconds(Long.MAX_VALUE)).build();
            Assert.fail("expected overflow lease to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("lease"));
        }
    }

    private void assertThreadTerminated(String threadName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 500L;
        while (System.currentTimeMillis() < deadline) {
            if (!isThreadAlive(threadName)) {
                return;
            }
            Thread.sleep(5L);
        }
        Assert.assertFalse(isThreadAlive(threadName));
    }

    private boolean isThreadAlive(String threadName) {
        Thread[] threads = new Thread[Thread.activeCount() + 8];
        Thread.enumerate(threads);
        for (Thread thread : threads) {
            if (thread != null && threadName.equals(thread.getName())) {
                return thread.isAlive();
            }
        }
        return false;
    }

    private void assertNoSecondWriteBack(FakeBackend backend) throws InterruptedException {
        // 否定性窗口：write-back 失败后不应再补写；50ms 内无新增即认定稳定
        long deadline = System.currentTimeMillis() + 50L;
        int seen = backend.closeCalls;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
            if (backend.closeCalls != seen) {
                break;
            }
        }
    }

    private void assertWriteBack(TaskResult result, CloseKind kind, String error) throws Exception {
        FakeBackend backend = new FakeBackend();
        CountDownLatch handled = new CountDownLatch(1);
        TaskWorker worker = worker(backend, new CountDownHandler(result, handled));

        worker.start();
        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        backend.awaitClose();

        Assert.assertEquals(1, backend.closeCalls);
        Assert.assertEquals(kind.status, backend.lastCompletion.getStatus());
        Assert.assertEquals(error, backend.lastCompletion.getErrorMessage());
        worker.shutdownNow();
    }

    private TaskWorker worker(FakeBackend backend, TaskHandler handler) {
        return queueWorker(backend, handler, false);
    }
    private TaskWorker queueWorker(FakeBackend backend, TaskHandler handler, boolean heartbeatEnabled) {
        TaskQueue queue = Leases.queue(backend, "orders");
        return queue.worker()
                .handle("email.send", handler)
                .workerId("worker-1")
                .lease(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(10))
                .heartbeatEnabled(heartbeatEnabled)
                .threadName("worker-test")
                .build();
    }

    private TaskWorker heartbeatWorker(FakeBackend backend, TaskHandler handler) {
        TaskQueue queue = Leases.queue(backend, "orders");
        return queue.worker()
                .handle("email.send", handler)
                .workerId("worker-1")
                .lease(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(10))
                .heartbeatEnabled(true)
                .heartbeatInterval(Duration.ofMillis(20))
                .threadName("worker-test")
                .build();
    }

    private TaskHandler handlerAwaitingHeartbeat(final CountDownLatch heartbeat) {
        return new TaskHandler() {
            @Override
            public TaskResult handle(TaskContext context) throws Exception {
                Assert.assertTrue(heartbeat.await(1, TimeUnit.SECONDS));
                return TaskResult.success();
            }
        };
    }
    private void assertBuildFails(TaskWorker.Builder builder) {
        try {
            builder.build();
            Assert.fail("expected builder validation to fail");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private enum CloseKind {
        SUCCEEDED(TaskStatus.SUCCEEDED),
        FAILED(TaskStatus.FAILED),
        CANCELLED(TaskStatus.CANCELLED);

        private final TaskStatus status;

        CloseKind(TaskStatus status) {
            this.status = status;
        }
    }

    private static class BlockedHandler implements TaskHandler {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private static BlockedHandler start() {
            return new BlockedHandler();
        }

        @Override
        public TaskResult handle(TaskContext context) throws Exception {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return TaskResult.success();
        }
    }

    private static class CountDownHandler implements TaskHandler {
        private final TaskResult result;
        private final CountDownLatch latch;

        private CountDownHandler(TaskResult result, CountDownLatch latch) {
            this.result = result;
            this.latch = latch;
        }

        @Override
        public TaskResult handle(TaskContext context) {
            Assert.assertEquals("task-1", context.getTaskId());
            Assert.assertEquals("orders", context.getQueue());
            Assert.assertEquals("email.send", context.getType());
            Assert.assertEquals(1, context.getAttemptCount());
            latch.countDown();
            return result;
        }
    }

    private static class WaitingHandler implements TaskHandler {
        private final TaskResult result;

        private WaitingHandler(TaskResult result) {
            this.result = result;
        }

        @Override
        public TaskResult handle(TaskContext context) {
            return result;
        }
    }

    private static class FakeBackend implements LeaseBackend {
        private volatile LeaseHandle handle;
        private volatile LeaseGrant lastGrant;
        private volatile boolean granted;
        private volatile boolean singleAcquire;
        private volatile int acquireCalls;
        private volatile int releaseCalls;
        private volatile int handlerInvocations;
        private volatile int closeCalls;
        private LeaseCompletion lastCompletion;
        private LeaseRetry lastRetry;
        private volatile long lastHeartbeatMillis = -1L;
        private volatile AcquireCommand lastAcquire;
        private CountDownLatch onAcquire;
        private volatile CountDownLatch acquireStarted;
        private volatile CountDownLatch acquireGranted;
        private volatile boolean acquireBlocked;
        private volatile boolean acquireRelease;
        private volatile CountDownLatch onHeartbeat;
        private volatile CountDownLatch onHeartbeatFailure;
        private volatile RuntimeResult[] heartbeatResults;
        private volatile int heartbeatFailuresBeforeSuccess;
        private volatile RuntimeResult lastHeartbeatResult;
        private volatile int heartbeatCalls;
        private volatile Exception closeFailure;
        private volatile Exception releaseFailure;
        private volatile RuntimeResult lastCloseResult;
        private final AtomicInteger closeInterrupted = new AtomicInteger();
        private volatile boolean closeGateOpen = true;
        private volatile boolean releaseGateOpen = true;
        private volatile CountDownLatch closeStarted;
        private volatile CountDownLatch releaseStarted;
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private final CountDownLatch releaseLatch = new CountDownLatch(1);

        private boolean awaitHeartbeatCount(int expected, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (System.currentTimeMillis() < deadline) {
                if (heartbeatCalls >= expected) {
                    return true;
                }
                Thread.sleep(5L);
            }
            return heartbeatCalls >= expected;
        }

        private boolean awaitCloseStarted(long timeout, TimeUnit unit)
                throws InterruptedException {
            return closeStarted != null && closeStarted.await(timeout, unit);
        }

        private boolean awaitReleaseStarted(long timeout, TimeUnit unit)
                throws InterruptedException {
            return releaseStarted != null && releaseStarted.await(timeout, unit);
        }

        @Override
        public SubmitResult submit(SubmitCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public LeaseGrant acquire(AcquireCommand command) throws InterruptedException {
            lastAcquire = command;
            acquireCalls++;
            if (acquireStarted != null) {
                acquireStarted.countDown();
            }
            if (!granted) {
                String workerId = command.getWorkerId();
                Assert.assertFalse(workerId == null || workerId.trim().isEmpty());
                Assert.assertTrue(command.getLeaseMillis() > 0L);
                TaskSubscription subscription = command.getSubscription();
                if (!"orders".equals(subscription.getQueue())
                        || !subscription.getTaskTypes().contains("email.send")) {
                    throw new AssertionError("invalid subscription: " + subscription.getTaskTypes());
                }
                granted = true;
                handle = LeaseHandle.of("task-1", workerId, "lease-1");
                LeaseGrant grant = LeaseGrant.of(handle, snapshot(workerId));
                lastGrant = grant;
                if (acquireGranted != null) {
                    acquireGranted.countDown();
                }
                if (acquireBlocked) {
                    while (!acquireRelease) {
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException ignored) {
                            Thread.interrupted();
                        }
                    }
                }
                return grant;
            }
            if (singleAcquire) {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(10L);
                }
                throw new InterruptedException("poll interrupted");
            }
            if (onAcquire != null) {
                onAcquire.countDown();
            }
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(10L);
            }
            throw new InterruptedException("poll interrupted");
        }

        @Override
        public RuntimeResult heartbeat(LeaseHandle leaseHandle, long extendMillis) {
            heartbeatCalls++;
            lastHeartbeatMillis = extendMillis;
            if (heartbeatFailuresBeforeSuccess > 0) {
                heartbeatFailuresBeforeSuccess--;
                if (onHeartbeatFailure != null) {
                    onHeartbeatFailure.countDown();
                }
                throw new IllegalStateException("heartbeat transport down");
            }
            RuntimeResult result = heartbeatResults == null ? RuntimeResult.APPLIED
                    : heartbeatResults[Math.min(heartbeatCalls - 1, heartbeatResults.length - 1)];
            lastHeartbeatResult = result;
            if (onHeartbeat != null) {
                onHeartbeat.countDown();
            }
            return result;
        }

        @Override
        public RuntimeResult release(LeaseHandle leaseHandle, LeaseRetry retry) throws Exception {
            Assert.assertSame(handle, leaseHandle);
            releaseCalls++;
            lastRetry = retry;
            if (releaseStarted != null) {
                releaseStarted.countDown();
            }
            while (!releaseGateOpen && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(10L);
            }
            releaseLatch.countDown();
            if (releaseFailure != null) {
                throw releaseFailure;
            }
            return RuntimeResult.APPLIED;
        }

        @Override
        public RuntimeResult close(LeaseHandle leaseHandle, LeaseCompletion completion) throws Exception {
            Assert.assertSame(handle, leaseHandle);
            closeCalls++;
            lastCompletion = completion;
            if (closeStarted != null) {
                closeStarted.countDown();
            }
            try {
                while (!closeGateOpen) {
                    Thread.sleep(10L);
                }
            } catch (InterruptedException ex) {
                closeInterrupted.incrementAndGet();
                throw ex;
            }
            lastCloseResult = RuntimeResult.APPLIED;
            closeLatch.countDown();
            if (closeFailure != null) {
                throw closeFailure;
            }
            return RuntimeResult.APPLIED;
        }

        @Override
        public AdminResult complete(AdminCompletionCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public AdminResult reschedule(RescheduleCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public AdminResult retry(RetryCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public AdminResult update(UpdateCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public AdminResult updateAndReschedule(UpdateCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public Optional<TaskSnapshot> get(String queue, String taskId) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public Optional<TaskSnapshot> getByDeduplicationKey(String queue, String taskType, String key) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.api.TaskPage list(String queue,
                com.team4u.framework.lease.api.TaskQuery query) {
            throw new UnsupportedOperationException("not expected");
        }

        private void awaitClose() throws InterruptedException {
            Assert.assertTrue(closeLatch.await(1, TimeUnit.SECONDS));
        }

        private void awaitRelease() throws InterruptedException {
            Assert.assertTrue(releaseLatch.await(1, TimeUnit.SECONDS));
        }

        private TaskSnapshot snapshot(String workerId) {
            return TaskSnapshot.builder()
                    .taskId("task-1")
                    .queue("orders")
                    .type("email.send")
                    .payload("{}")
                    .dedupKey("order-1")
                    .status(TaskStatus.RUNNING)
                    .workerId(workerId)
                    .priority(3)
                    .attemptCount(1)
                    .createdAt(Instant.EPOCH)
                    .visibleAt(Instant.EPOCH)
                    .leaseExpiresAt(Instant.EPOCH.plusMillis(1000))
                    .attribute("traceId", "trace-1")
                    .build();
        }
    }
}
