package com.team4u.framework.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.model.*;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public abstract class AbstractLeaseBackendContractTest {

    protected static final String DEFAULT_QUEUE = "default";

    protected abstract com.team4u.framework.lease.api.LeaseBackend createBackend();

    @Test
    public void testPublishAndAcquireReadyTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "{\"id\":1}");

        LeaseGrant grant = acquire(backend, "worker-a", 200L, 500L);

        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getTaskId());
        Assert.assertEquals(DEFAULT_QUEUE, grant.getQueue());
        Assert.assertEquals("pay", grant.getTaskType());
        Assert.assertEquals("{\"id\":1}", grant.getPayload());
        Assert.assertEquals(1, grant.getDeliveryCount());
        Assert.assertEquals(0, grant.getFailureCount());
    }

    @Test
    public void testOnlyOneWorkerCanAcquireSameTask() throws Exception {
        final com.team4u.framework.lease.api.LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload");

        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final LeaseGrant[] grants = new LeaseGrant[2];

        Thread first = createAcquireThread(backend, ready, start, grants, 0, "worker-a");
        Thread second = createAcquireThread(backend, ready, start, grants, 1, "worker-b");
        first.start();
        second.start();

        Assert.assertTrue(ready.await(1, TimeUnit.SECONDS));
        start.countDown();
        first.join();
        second.join();

        Assert.assertTrue((grants[0] == null) ^ (grants[1] == null));
    }

    @Test
    public void testAckRemovesTaskFromFutureAcquisition() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 200L, 500L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.ack(grant.getHandle()));

        Assert.assertNull(acquire(backend, "worker-b", 200L, 100L));
    }

    @Test
    public void testFailMakesTaskTerminal() throws Exception {
        LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 200L, 500L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.fail(
                grant.getHandle(), LeaseFailureRequest.of(new IllegalStateException("boom"))));

        Assert.assertNull(acquire(backend, "worker-b", 200L, 100L));
    }

    @Test
    public void testWrongLeaseTokenDoesNotMutateTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 120L, 500L);
        LeaseHandle wrongHandle = new LeaseHandle(taskId, grant.getHandle().getWorkerId(), "wrong-token");

        Assert.assertEquals(LeaseRuntimeResult.LEASE_LOST, backend.ack(wrongHandle));
        Assert.assertEquals(LeaseRuntimeResult.LEASE_LOST,
                backend.fail(wrongHandle, LeaseFailureRequest.of(new IllegalStateException("wrong"))));
        Assert.assertEquals(LeaseRuntimeResult.LEASE_LOST, backend.heartbeat(wrongHandle, 500L));

        Thread.sleep(150L);
        LeaseGrant reacquired = acquire(backend, "worker-b", 120L, 300L);
        Assert.assertNotNull(reacquired);
        Assert.assertEquals(2, reacquired.getDeliveryCount());
        Assert.assertEquals(0, reacquired.getFailureCount());
    }

    @Test
    public void testHeartbeatExtendsLease() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 80L, 500L);

        Thread.sleep(40L);
        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.heartbeat(grant.getHandle(), 150L));
        Thread.sleep(70L);

        Assert.assertNull(acquire(backend, "worker-b", 80L, 20L));
        Thread.sleep(110L);
        Assert.assertNotNull(acquire(backend, "worker-b", 80L, 200L));
    }

    @Test
    public void testLeaseExpiryMakesTaskVisibleAgainWithoutFailureIncrement() throws Exception {
        LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload");
        acquire(backend, "worker-a", 80L, 500L);

        Thread.sleep(100L);

        LeaseGrant nextGrant = acquire(backend, "worker-b", 80L, 200L);
        Assert.assertNotNull(nextGrant);
        Assert.assertEquals(2, nextGrant.getDeliveryCount());
        Assert.assertEquals(0, nextGrant.getFailureCount());
    }

    @Test
    public void testReleaseMakesTaskVisibleAgainWithoutFailureIncrement() throws Exception {
        LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 80L, 500L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED,
                backend.release(grant.getHandle(), LeaseReleaseRequest.of(50L)));
        Assert.assertNull(acquire(backend, "worker-b", 80L, 20L));
        Thread.sleep(70L);

        LeaseGrant nextGrant = acquire(backend, "worker-b", 80L, 200L);
        Assert.assertNotNull(nextGrant);
        Assert.assertEquals(2, nextGrant.getDeliveryCount());
        Assert.assertEquals(0, nextGrant.getFailureCount());
    }

    @Test
    public void testAcquireRespectsDelayVisibility() throws Exception {
        LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload", 80L);

        Assert.assertNull(acquire(backend, "worker-a", 100L, 20L));
        Thread.sleep(90L);
        Assert.assertNotNull(acquire(backend, "worker-a", 100L, 200L));
    }

    @Test
    public void testAcquireOnlyReturnsSubscribedQueue() throws Exception {
        LeaseBackend backend = createBackend();
        backend.publish(LeasePublishRequest.builder().queue("mail").taskType("send").payload("payload").build());

        Assert.assertNull(acquire(backend, "worker-a", 100L, 20L));
        Assert.assertNotNull(backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-a")
                .leaseMillis(100L)
                .waitTimeoutMillis(200L)
                .subscription(LeaseSubscription.builder().queue("mail").build())
                .build()));
    }

    private Thread createAcquireThread(final com.team4u.framework.lease.api.LeaseBackend backend,
                                       final CountDownLatch ready,
                                       final CountDownLatch start,
                                       final LeaseGrant[] grants,
                                       final int index,
                                       final String workerId) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                ready.countDown();
                try {
                    start.await(1, TimeUnit.SECONDS);
                    grants[index] = acquire(backend, workerId, 500L, 200L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    protected String publish(com.team4u.framework.lease.api.LeaseBackend backend, String taskType, String payload) {
        return publish(backend, taskType, payload, 0L);
    }

    protected String publish(com.team4u.framework.lease.api.LeaseBackend backend, String taskType, String payload,
                             long delayMillis) {
        return backend.publish(LeasePublishRequest.builder()
                .queue(DEFAULT_QUEUE)
                .taskType(taskType)
                .payload(payload)
                .delayMillis(delayMillis)
                .build());
    }

    protected LeaseGrant acquire(com.team4u.framework.lease.api.LeaseBackend backend, String workerId, long leaseMillis,
                                 long waitTimeoutMillis) throws Exception {
        return backend.acquire(LeaseAcquireRequest.builder()
                .workerId(workerId)
                .leaseMillis(leaseMillis)
                .waitTimeoutMillis(waitTimeoutMillis)
                .subscription(LeaseSubscription.builder().queue(DEFAULT_QUEUE).build())
                .build());
    }

    protected LeaseGrant acquire(com.team4u.framework.lease.api.LeaseBackend backend, String workerId, long leaseMillis,
                                 long waitTimeoutMillis, String... ignoredTaskTypes) throws Exception {
        return acquire(backend, workerId, leaseMillis, waitTimeoutMillis);
    }
}
