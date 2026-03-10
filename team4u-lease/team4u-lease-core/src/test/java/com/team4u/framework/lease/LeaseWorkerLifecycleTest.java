package com.team4u.framework.lease;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.handler.LeaseTaskHandlerRegistry;
import com.team4u.framework.lease.model.*;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LeaseWorkerLifecycleTest {

    @Test
    public void testWorkerSkipsDefaultCloseWhenHandlerManagesLifecycle() throws Exception {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        CountDownLatch handled = new CountDownLatch(1);
        LeaseTaskHandler handler = new LeaseTaskHandler() {
            @Override
            public void handle(LeaseExecutionContext context) {
                context.markLifecycleHandled();
                handled.countDown();
            }
        };
        LeaseWorker worker = new LeaseWorker(
                runtimeClient,
                new SingleHandlerRegistry(handler),
                LeaseWorkerPolicy.builder().pollWaitMillis(10L).heartbeatEnabled(false).build());

        worker.start("lease-worker-lifecycle-test");

        Assert.assertTrue(handled.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(runtimeClient.awaitCloseCheck(1, TimeUnit.SECONDS));
        Assert.assertEquals(0, runtimeClient.closeCalls);

        worker.shutdownNow();
    }

    private static class SingleHandlerRegistry implements LeaseTaskHandlerRegistry {
        private final LeaseTaskHandler handler;

        private SingleHandlerRegistry(LeaseTaskHandler handler) {
            this.handler = handler;
        }

        @Override
        public void register(String queue, String taskType, LeaseTaskHandler handler) {
        }

        @Override
        public Optional<LeaseTaskHandler> get(String queue, String taskType) {
            return Optional.of(handler);
        }

        @Override
        public Set<LeaseSubscription> subscriptions() {
            return Collections.singleton(LeaseSubscription.builder().queue("retry-q").build());
        }
    }

    private static class TrackingRuntimeClient implements LeaseRuntimeClient {
        private final CountDownLatch closeCheckLatch = new CountDownLatch(1);
        private volatile boolean granted;
        private int closeCalls;

        @Override
        public LeaseGrant acquire(LeaseAcquireRequest request) throws InterruptedException {
            if (!granted) {
                granted = true;
                return LeaseGrant.builder()
                        .taskId("task-1")
                        .workerId(request.getWorkerId())
                        .leaseToken("lease-1")
                        .queue("retry-q")
                        .taskType("recover-payment")
                        .payload("{}")
                        .build();
            }
            closeCheckLatch.countDown();
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(10L);
            }
            throw new InterruptedException("interrupted");
        }

        @Override
        public LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
            closeCalls++;
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

        private boolean awaitCloseCheck(long timeout, TimeUnit unit) throws InterruptedException {
            return closeCheckLatch.await(timeout, unit);
        }
    }
}
