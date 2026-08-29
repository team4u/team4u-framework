package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import org.junit.Assert;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public abstract class AbstractLeaseContractSupport {

    protected static final String DEFAULT_QUEUE = "orders";
    protected static final String PAY_TASK_TYPE = "pay";
    protected static final String MAIL_TASK_TYPE = "mail";
    protected static final String WORKER_A = "worker-a";
    protected static final String WORKER_B = "worker-b";

    // 时间标尺整体缩小 10 倍：这些常量只表达「短租约 / 长租约 / 短延迟」的相对语义，
    // 缩放后契约不变，过期等待从 ~525ms 降到 ~60ms 量级。短延迟取 50ms 而非 25ms，
    // 为提交后立即断言「延迟未到不可见」的否定性检查留出调度抖动余量。
    protected static final long LEASE_MILLIS = 50L;
    protected static final long LONG_LEASE_MILLIS = 200L;
    protected static final long SHORT_DELAY_MILLIS = 50L;
    protected static final long VISIBILITY_MARGIN_MILLIS = 10L;

    protected abstract LeaseBackend createBackend();

    protected String submit(LeaseBackend backend, String taskType, String payload) {
        return submit(backend, DEFAULT_QUEUE, taskType, payload, null, 0L,
                Collections.<String, String>emptyMap());
    }

    protected String submit(LeaseBackend backend, String queue, String taskType, String payload) {
        return submit(backend, queue, taskType, payload, null, 0L,
                Collections.<String, String>emptyMap());
    }

    protected String submit(LeaseBackend backend, String queue, String taskType, String payload,
                            String deduplicationKey, long delayMillis, Map<String, String> attributes) {
        return backend.submit(SubmitCommand.of(queue, taskType, payload, deduplicationKey,
                delayMillis, 0, attributes)).getTaskId();
    }

    protected String submit(LeaseBackend backend, String queue, String taskType, String payload,
                            int priority) {
        return backend.submit(SubmitCommand.of(queue, taskType, payload, null, 0L, priority,
                Collections.<String, String>emptyMap())).getTaskId();
    }

    protected String submit(LeaseBackend backend, String queue, String taskType, String payload,
                            String deduplicationKey, long delayMillis, Map<String, String> attributes,
                            int priority) {
        return backend.submit(SubmitCommand.of(queue, taskType, payload, deduplicationKey,
                delayMillis, priority, attributes)).getTaskId();
    }

    protected SubmitResult submitResult(LeaseBackend backend, String queue, String taskType, String payload,
                                        String deduplicationKey, long delayMillis, Map<String, String> attributes) {
        return backend.submit(SubmitCommand.of(queue, taskType, payload, deduplicationKey,
                delayMillis, 0, attributes));
    }

    protected LeaseGrant acquire(LeaseBackend backend, String taskType, String workerId,
                                 long leaseMillis) throws InterruptedException {
        return acquireFromQueue(backend, DEFAULT_QUEUE, taskType, workerId, leaseMillis);
    }

    protected LeaseGrant acquireFromQueue(LeaseBackend backend, String queue, String taskType,
                                          String workerId, long leaseMillis) throws InterruptedException {
        return backend.acquire(AcquireCommand.of(
                TaskSubscription.of(queue, Collections.singleton(taskType)), workerId, leaseMillis));
    }

    protected LeaseGrant assertRunningGrant(LeaseGrant grant, String taskId, String workerId) {
        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getHandle().getTaskId());
        Assert.assertEquals(workerId, grant.getHandle().getWorkerId());
        Assert.assertNotNull(grant.getHandle().getLeaseToken());
        Assert.assertEquals(taskId, grant.getSnapshot().getTaskId());
        Assert.assertEquals(TaskStatus.RUNNING, grant.getSnapshot().getStatus());
        Assert.assertEquals(workerId, grant.getSnapshot().getWorkerId());
        return grant;
    }

    protected TaskSnapshot snapshot(LeaseBackend backend, String queue, String taskId) {
        TaskSnapshot snapshot = backend.get(queue, taskId).orElse(null);
        Assert.assertNotNull(snapshot);
        return snapshot;
    }

    protected Thread startAcquire(LeaseBackend backend, String queue, String taskType,
                                  String workerId, long leaseMillis, LeaseGrant[] grantHolder) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    grantHolder[0] = acquireFromQueue(backend, queue, taskType, workerId, leaseMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "contract-acquire-" + workerId);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    protected void waitUntil(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000L);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(LeaseTestWaits.POLL_INTERVAL_MILLIS);
        }
        Assert.fail("condition was not met within 2000ms");
    }

    protected void waitUntilAfter(Instant boundary) throws InterruptedException {
        LeaseTestWaits.awaitAfter(boundary, VISIBILITY_MARGIN_MILLIS);
    }
}
