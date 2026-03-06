package com.team4u.framework.lease;

import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public abstract class AbstractLeaseBackendContractTest {

    protected abstract LeaseBackend createBackend();

    protected InMemoryLeaseBackend asInMemory(LeaseBackend backend) {
        return (InMemoryLeaseBackend) backend;
    }

    @Test
    public void testPublishAndAcquireReadyTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = backend.publish("pay", "{\"id\":1}");

        LeaseGrant grant = backend.acquire("worker-a", 200L, 500L);

        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getTaskId());
        Assert.assertEquals("pay", grant.getTaskType());
        Assert.assertEquals("{\"id\":1}", grant.getPayload());
        Assert.assertEquals(1, grant.getAttemptCount());
    }

    @Test
    public void testOnlyOneWorkerCanAcquireSameTask() throws Exception {
        final LeaseBackend backend = createBackend();
        backend.publish("pay", "payload");

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
        String taskId = backend.publish("pay", "payload");
        LeaseGrant grant = backend.acquire("worker-a", 200L, 500L);

        backend.ack(taskId, "worker-a", grant.getLeaseToken());

        Assert.assertNull(backend.acquire("worker-b", 200L, 100L));
    }

    @Test
    public void testRetryMakesTaskVisibleAgain() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = backend.publish("pay", "payload");
        LeaseGrant grant = backend.acquire("worker-a", 200L, 500L);

        backend.retry(taskId, "worker-a", grant.getLeaseToken(), 50L, new IllegalStateException("boom"));

        Assert.assertNull(backend.acquire("worker-b", 200L, 20L));
        Thread.sleep(80L);

        LeaseGrant nextGrant = backend.acquire("worker-b", 200L, 200L);
        Assert.assertNotNull(nextGrant);
        Assert.assertEquals(2, nextGrant.getAttemptCount());
        Assert.assertEquals(taskId, nextGrant.getTaskId());
    }

    @Test
    public void testFailMakesTaskTerminal() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = backend.publish("pay", "payload");
        LeaseGrant grant = backend.acquire("worker-a", 200L, 500L);

        backend.fail(taskId, "worker-a", grant.getLeaseToken(), new IllegalStateException("boom"));

        Assert.assertNull(backend.acquire("worker-b", 200L, 100L));
    }

    @Test
    public void testWrongLeaseTokenDoesNotMutateTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = backend.publish("pay", "payload");
        LeaseGrant grant = backend.acquire("worker-a", 120L, 500L);

        backend.ack(taskId, "worker-a", "wrong-token");
        backend.retry(taskId, "worker-a", "wrong-token", 0L, new IllegalStateException("wrong"));
        backend.fail(taskId, "worker-a", "wrong-token", new IllegalStateException("wrong"));
        backend.heartbeat(taskId, "worker-a", "wrong-token", 500L);

        Thread.sleep(150L);
        LeaseGrant reacquired = backend.acquire("worker-b", 120L, 300L);
        Assert.assertNotNull(reacquired);
        Assert.assertEquals(2, reacquired.getAttemptCount());
    }

    @Test
    public void testHeartbeatExtendsLease() throws Exception {
        LeaseBackend backend = createBackend();
        backend.publish("pay", "payload");
        LeaseGrant grant = backend.acquire("worker-a", 80L, 500L);

        Thread.sleep(40L);
        backend.heartbeat(grant.getTaskId(), "worker-a", grant.getLeaseToken(), 150L);
        Thread.sleep(70L);

        Assert.assertNull(backend.acquire("worker-b", 80L, 20L));
        Thread.sleep(110L);
        Assert.assertNotNull(backend.acquire("worker-b", 80L, 200L));
    }

    @Test
    public void testLeaseExpiryMakesTaskVisibleAgain() throws Exception {
        LeaseBackend backend = createBackend();
        backend.publish("pay", "payload");
        backend.acquire("worker-a", 80L, 500L);

        Thread.sleep(100L);

        LeaseGrant nextGrant = backend.acquire("worker-b", 80L, 200L);
        Assert.assertNotNull(nextGrant);
        Assert.assertEquals(2, nextGrant.getAttemptCount());
    }

    @Test
    public void testAcquireRespectsDelayVisibility() throws Exception {
        LeaseBackend backend = createBackend();
        backend.publish("pay", "payload", 80L);

        Assert.assertNull(backend.acquire("worker-a", 100L, 20L));
        Thread.sleep(90L);
        Assert.assertNotNull(backend.acquire("worker-a", 100L, 200L));
    }

    private Thread createAcquireThread(final LeaseBackend backend,
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
                    grants[index] = backend.acquire(workerId, 500L, 200L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
