package com.team4u.framework.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.model.*;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 租约运行时功能契约测试基类
 * <p>
 * 涵盖了租约生命周期的核心流程：发布、获取、续约（心跳）、关闭以及自动过期逻辑。
 */
public abstract class AbstractLeaseRuntimeContractTest extends AbstractLeaseContractSupport {

    /**
     * 测试最基本的发布与获取逻辑。
     */
    @Test
    public void testPublishAndAcquireReadyTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "{\"id\":1}");

        // 尝试获取任务，租约有效期 200ms，等待超时 500ms
        LeaseGrant grant = acquire(backend, "worker-a", 200L, 500L);

        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getTaskId());
        Assert.assertEquals(DEFAULT_QUEUE, grant.getTaskGroup());
        Assert.assertEquals("pay", grant.getTaskType());
        Assert.assertEquals("{\"id\":1}", grant.getPayload());
        Assert.assertEquals(1, grant.getDeliveryCount());
        Assert.assertEquals(0, grant.getFailureCount());
    }

    /**
     * 测试并发获取任务时的原子性，确保同一个任务只有一个 worker 能够成功获取。
     */
    @Test
    public void testOnlyOneWorkerCanAcquireSameTask() throws Exception {
        final LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload");

        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final LeaseGrant[] grants = new LeaseGrant[2];

        Thread first = createAcquireThread(backend, ready, start, grants, 0, "worker-a");
        Thread second = createAcquireThread(backend, ready, start, grants, 1, "worker-b");
        first.start();
        second.start();

        // 等待所有线程就绪
        Assert.assertTrue(ready.await(1, TimeUnit.SECONDS));
        // 发令起跑
        start.countDown();
        first.join();
        second.join();

        // 验证有且仅有一个 worker 获取到了租约
        Assert.assertTrue((grants[0] == null) ^ (grants[1] == null));
    }

    @Test
    public void testCloseSuccessRemovesTaskFromFutureAcquisition() throws Exception {
        LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 200L, 500L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED,
                backend.close(grant.getHandle(), LeaseCloseRequest.succeeded()));
        Assert.assertNull(acquire(backend, "worker-b", 200L, 100L));
    }

    @Test
    public void testCloseFailureMakesTaskClosed() throws Exception {
        LeaseBackend backend = createBackend();
        publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 200L, 500L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.close(
                grant.getHandle(), LeaseCloseRequest.failed(LeaseTaskFailureReason.HANDLER_EXCEPTION, "boom")));
        Assert.assertNull(acquire(backend, "worker-b", 200L, 100L));
    }

    @Test
    public void testCloseCanUpdatePayload() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload-v1");
        LeaseGrant grant = acquire(backend, "worker-a", 200L, 500L);

        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.close(
                grant.getHandle(),
                LeaseCloseRequest.builder()
                        .outcome(LeaseTaskOutcome.SUCCEEDED)
                        .payload("payload-v2")
                        .build()));
        Assert.assertEquals("payload-v2", backend.get(taskId).get().getPayload());
    }

    @Test
    public void testWrongLeaseTokenDoesNotMutateTask() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload");
        LeaseGrant grant = acquire(backend, "worker-a", 120L, 500L);
        LeaseHandle wrongHandle = new LeaseHandle(taskId, grant.getHandle().getWorkerId(), "wrong-token");

        Assert.assertEquals(LeaseRuntimeResult.LEASE_LOST,
                backend.close(wrongHandle,
                        LeaseCloseRequest.failed(LeaseTaskFailureReason.HANDLER_EXCEPTION, "wrong")));
        Assert.assertEquals(LeaseRuntimeResult.LEASE_LOST,
                backend.release(wrongHandle, LeaseReleaseRequest.of(50L, "wrong")));
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
        publish(backend, "pay", "payload");
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
    public void testReleaseCanUpdatePayload() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = publish(backend, "pay", "payload-v1");
        LeaseGrant grant = acquire(backend, "worker-a", 80L, 500L);

        Assert.assertEquals(
                LeaseRuntimeResult.APPLIED,
                backend.release(grant.getHandle(), LeaseReleaseRequest.builder()
                        .delayMillis(50L)
                        .payload("payload-v2")
                        .errorMessage("retry")
                        .build()));

        Thread.sleep(70L);
        LeaseGrant nextGrant = acquire(backend, "worker-b", 80L, 200L);
        Assert.assertNotNull(nextGrant);
        Assert.assertEquals("payload-v2", nextGrant.getPayload());
        Assert.assertEquals("payload-v2", backend.get(taskId).get().getPayload());
        Assert.assertEquals("retry", backend.get(taskId).get().getErrorMessage());
    }

    @Test
    public void testReleaseCanUpdateAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = backend.publish(LeasePublishRequest.builder()
                .taskGroup(DEFAULT_QUEUE)
                .taskType("pay")
                .payload("payload-v1")
                .attributes(Collections.singletonMap("traceId", "T-1"))
                .build());
        LeaseGrant grant = acquire(backend, "worker-a", 80L, 500L);

        Assert.assertEquals(
                LeaseRuntimeResult.APPLIED,
                backend.release(grant.getHandle(), LeaseReleaseRequest.builder()
                        .delayMillis(50L)
                        .attributes(Collections.singletonMap("traceId", "T-2"))
                        .build()));

        Thread.sleep(70L);
        LeaseGrant nextGrant = acquire(backend, "worker-b", 80L, 200L);
        Assert.assertNotNull(nextGrant);
        Assert.assertEquals("T-2", nextGrant.getAttributes().get("traceId"));
        Assert.assertEquals("T-2", backend.get(taskId).get().getAttributes().get("traceId"));
    }

    @Test
    public void testReleaseWithoutAttributesKeepsOriginalAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = backend.publish(LeasePublishRequest.builder()
                .taskGroup(DEFAULT_QUEUE)
                .taskType("pay")
                .payload("payload-v1")
                .attributes(Collections.singletonMap("traceId", "T-1"))
                .build());
        LeaseGrant grant = acquire(backend, "worker-a", 80L, 500L);

        Assert.assertEquals(
                LeaseRuntimeResult.APPLIED,
                backend.release(grant.getHandle(), LeaseReleaseRequest.of(50L)));

        Thread.sleep(70L);
        LeaseGrant nextGrant = acquire(backend, "worker-b", 80L, 200L);
        Assert.assertNotNull(nextGrant);
        Assert.assertEquals("T-1", nextGrant.getAttributes().get("traceId"));
        Assert.assertEquals("T-1", backend.get(taskId).get().getAttributes().get("traceId"));
    }

    @Test
    public void testReleaseWithEmptyAttributesKeepsOriginalAttributes() throws Exception {
        LeaseBackend backend = createBackend();
        String taskId = backend.publish(LeasePublishRequest.builder()
                .taskGroup(DEFAULT_QUEUE)
                .taskType("pay")
                .payload("payload-v1")
                .attributes(Collections.singletonMap("traceId", "T-1"))
                .build());
        LeaseGrant grant = acquire(backend, "worker-a", 80L, 500L);

        Assert.assertEquals(
                LeaseRuntimeResult.APPLIED,
                backend.release(grant.getHandle(), LeaseReleaseRequest.builder()
                        .delayMillis(50L)
                        .attributes(Collections.emptyMap())
                        .build()));

        Thread.sleep(70L);
        LeaseGrant nextGrant = acquire(backend, "worker-b", 80L, 200L);
        Assert.assertNotNull(nextGrant);
        Assert.assertEquals("T-1", nextGrant.getAttributes().get("traceId"));
        Assert.assertEquals("T-1", backend.get(taskId).get().getAttributes().get("traceId"));
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
        backend.publish(com.team4u.framework.lease.model.LeasePublishRequest.builder()
                .taskGroup("mail")
                .taskType("send")
                .payload("payload")
                .build());

        Assert.assertNull(acquire(backend, "worker-a", 100L, 20L));
        Assert.assertNotNull(backend.acquire(LeaseAcquireRequest.builder()
                .workerId("worker-a")
                .leaseMillis(100L)
                .waitTimeoutMillis(200L)
                .subscription(LeaseTaskGroupSubscription.builder().taskGroup("mail").build())
                .build()));
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
                    grants[index] = acquire(backend, workerId, 500L, 200L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
