package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeaseGrant;
import com.team4u.framework.lease.LeaseTaskStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 内存版租约后端。
 * <p>
 * 该类是 {@link LeaseBackend} 的单进程内存实现，主要用于单元测试、演示或轻量级本地任务调度。
 * 它利用 {@link DelayQueue} 管理任务的可见性和租赁过期，并通过 {@link ConcurrentHashMap} 持久化任务记录。
 */
public class InMemoryLeaseBackend implements LeaseBackend {

    private final ConcurrentMap<String, StoredTask> records = new ConcurrentHashMap<String, StoredTask>();
    private final DelayQueue<AvailabilityRef> queue = new DelayQueue<AvailabilityRef>();

    @Override
    public String publish(String taskType, String payload) {
        return publish(taskType, payload, 0L);
    }

    @Override
    public synchronized String publish(String taskType, String payload, long delayMillis) {
        long now = System.currentTimeMillis();
        String taskId = nextTaskId();
        StoredTask task = new StoredTask(
                taskId,
                taskType,
                payload,
                now,
                now + Math.max(0L, delayMillis),
                0,
                Collections.<String, String>emptyMap(),
                LeaseTaskStatus.SCHEDULED,
                null,
                null,
                0L,
                null
        );
        records.put(taskId, task);
        queue.offer(new AvailabilityRef(taskId, task.getVisibleAtMillis()));
        return taskId;
    }

    @Override
    public synchronized void reschedule(String taskId, long delayMillis) {
        StoredTask current = records.get(taskId);
        if (current == null || isTerminal(current)) {
            return;
        }
        long now = System.currentTimeMillis();
        StoredTask next = current.withSchedule(now + Math.max(0L, delayMillis), current.getLastError());
        records.put(taskId, next);
        queue.offer(new AvailabilityRef(taskId, next.getVisibleAtMillis()));
    }

    @Override
    public synchronized void cancel(String taskId) {
        StoredTask current = records.get(taskId);
        if (current == null || isTerminal(current)) {
            return;
        }
        records.put(taskId, current.withTerminal(LeaseTaskStatus.DEAD, "cancelled"));
    }

    @Override
    public LeaseGrant acquire(String workerId, long leaseMillis, long waitTimeoutMillis) throws InterruptedException {
        long timeout = Math.max(0L, waitTimeoutMillis);
        long deadline = System.currentTimeMillis() + timeout;
        while (true) {
            AvailabilityRef ref = pollRef(deadline, timeout);
            if (ref == null) {
                return null;
            }

            LeaseGrant grant = claim(ref, workerId, leaseMillis);
            if (grant != null) {
                return grant;
            }
        }
    }

    @Override
    public synchronized void ack(String taskId, String workerId, String leaseToken) {
        StoredTask current = records.get(taskId);
        if (!matchesLease(current, workerId, leaseToken)) {
            return;
        }
        records.put(taskId, current.withTerminal(LeaseTaskStatus.SUCCEEDED, current.getLastError()));
    }

    @Override
    public synchronized void retry(String taskId, String workerId, String leaseToken, long delayMillis, Throwable cause) {
        StoredTask current = records.get(taskId);
        if (!matchesLease(current, workerId, leaseToken)) {
            return;
        }
        long visibleAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
        StoredTask next = current.withSchedule(visibleAt, errorMessage(cause));
        records.put(taskId, next);
        queue.offer(new AvailabilityRef(taskId, next.getVisibleAtMillis()));
    }

    @Override
    public synchronized void fail(String taskId, String workerId, String leaseToken, Throwable cause) {
        StoredTask current = records.get(taskId);
        if (!matchesLease(current, workerId, leaseToken)) {
            return;
        }
        records.put(taskId, current.withTerminal(LeaseTaskStatus.DEAD, errorMessage(cause)));
    }

    @Override
    public synchronized void heartbeat(String taskId, String workerId, String leaseToken, long extendMillis) {
        StoredTask current = records.get(taskId);
        if (!matchesLease(current, workerId, leaseToken)) {
            return;
        }
        long nextLeaseExpiresAt = System.currentTimeMillis() + Math.max(0L, extendMillis);
        StoredTask next = current.withLease(workerId, leaseToken, nextLeaseExpiresAt);
        records.put(taskId, next);
        queue.offer(new AvailabilityRef(taskId, next.getLeaseExpiresAtMillis()));
    }

    public synchronized Map<String, StoredTask> snapshot() {
        Map<String, StoredTask> snapshot = new LinkedHashMap<String, StoredTask>();
        for (Map.Entry<String, StoredTask> entry : records.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().copy());
        }
        return snapshot;
    }

    private AvailabilityRef pollRef(long deadline, long waitTimeoutMillis) throws InterruptedException {
        if (waitTimeoutMillis <= 0L) {
            return queue.poll();
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0L) {
            return null;
        }
        return queue.poll(remaining, TimeUnit.MILLISECONDS);
    }

    private synchronized LeaseGrant claim(AvailabilityRef ref, String workerId, long leaseMillis) {
        StoredTask current = records.get(ref.taskId);
        if (current == null || isTerminal(current)) {
            return null;
        }
        long now = System.currentTimeMillis();
        long availableAt = current.getStatus() == LeaseTaskStatus.LEASED
                ? current.getLeaseExpiresAtMillis()
                : current.getVisibleAtMillis();
        if (availableAt != ref.availableAtMillis || availableAt > now) {
            return null;
        }

        String leaseToken = nextLeaseToken();
        long leaseExpiresAt = now + Math.max(1L, leaseMillis);
        StoredTask leased = current.claim(workerId, leaseToken, leaseExpiresAt);
        records.put(ref.taskId, leased);
        queue.offer(new AvailabilityRef(ref.taskId, leaseExpiresAt));
        return leased.toGrant();
    }

    private boolean matchesLease(StoredTask current, String workerId, String leaseToken) {
        if (current == null || current.getStatus() != LeaseTaskStatus.LEASED) {
            return false;
        }
        if (!stringEquals(current.getWorkerId(), workerId) || !stringEquals(current.getLeaseToken(), leaseToken)) {
            return false;
        }
        return current.getLeaseExpiresAtMillis() >= System.currentTimeMillis();
    }

    private boolean isTerminal(StoredTask current) {
        return current.getStatus() == LeaseTaskStatus.SUCCEEDED || current.getStatus() == LeaseTaskStatus.DEAD;
    }

    private String nextTaskId() {
        return "lease-task-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String nextLeaseToken() {
        return "lease-token-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String errorMessage(Throwable cause) {
        return cause == null ? null : String.valueOf(cause);
    }

    private boolean stringEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static class AvailabilityRef implements Delayed {
        private final String taskId;
        private final long availableAtMillis;

        private AvailabilityRef(String taskId, long availableAtMillis) {
            this.taskId = taskId;
            this.availableAtMillis = availableAtMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long diff = availableAtMillis - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            AvailabilityRef o = (AvailabilityRef) other;
            return Long.compare(this.availableAtMillis, o.availableAtMillis);
        }
    }

    /**
     * 调试与测试辅助视图。
     */
    public static final class StoredTask {
        private final String taskId;
        private final String taskType;
        private final String payload;
        private final long createdAtMillis;
        private final long visibleAtMillis;
        private final int attemptCount;
        private final Map<String, String> attributes;
        private final LeaseTaskStatus status;
        private final String workerId;
        private final String leaseToken;
        private final long leaseExpiresAtMillis;
        private final String lastError;

        private StoredTask(String taskId,
                           String taskType,
                           String payload,
                           long createdAtMillis,
                           long visibleAtMillis,
                           int attemptCount,
                           Map<String, String> attributes,
                           LeaseTaskStatus status,
                           String workerId,
                           String leaseToken,
                           long leaseExpiresAtMillis,
                           String lastError) {
            this.taskId = taskId;
            this.taskType = taskType;
            this.payload = payload;
            this.createdAtMillis = createdAtMillis;
            this.visibleAtMillis = visibleAtMillis;
            this.attemptCount = attemptCount;
            this.attributes = attributes == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
            this.status = status;
            this.workerId = workerId;
            this.leaseToken = leaseToken;
            this.leaseExpiresAtMillis = leaseExpiresAtMillis;
            this.lastError = lastError;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getTaskType() {
            return taskType;
        }

        public String getPayload() {
            return payload;
        }

        public long getCreatedAtMillis() {
            return createdAtMillis;
        }

        public long getVisibleAtMillis() {
            return visibleAtMillis;
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public LeaseTaskStatus getStatus() {
            return status;
        }

        public String getWorkerId() {
            return workerId;
        }

        public String getLeaseToken() {
            return leaseToken;
        }

        public long getLeaseExpiresAtMillis() {
            return leaseExpiresAtMillis;
        }

        public String getLastError() {
            return lastError;
        }

        private StoredTask copy() {
            return new StoredTask(taskId, taskType, payload, createdAtMillis, visibleAtMillis, attemptCount,
                    attributes, status, workerId, leaseToken, leaseExpiresAtMillis, lastError);
        }

        private StoredTask claim(String workerId, String leaseToken, long leaseExpiresAtMillis) {
            return new StoredTask(taskId, taskType, payload, createdAtMillis, visibleAtMillis, attemptCount + 1,
                    attributes, LeaseTaskStatus.LEASED, workerId, leaseToken, leaseExpiresAtMillis, lastError);
        }

        private StoredTask withSchedule(long visibleAtMillis, String lastError) {
            return new StoredTask(taskId, taskType, payload, createdAtMillis, visibleAtMillis, attemptCount,
                    attributes, LeaseTaskStatus.SCHEDULED, null, null, 0L, lastError);
        }

        private StoredTask withLease(String workerId, String leaseToken, long leaseExpiresAtMillis) {
            return new StoredTask(taskId, taskType, payload, createdAtMillis, visibleAtMillis, attemptCount,
                    attributes, LeaseTaskStatus.LEASED, workerId, leaseToken, leaseExpiresAtMillis, lastError);
        }

        private StoredTask withTerminal(LeaseTaskStatus status, String lastError) {
            return new StoredTask(taskId, taskType, payload, createdAtMillis, visibleAtMillis, attemptCount,
                    attributes, status, null, null, 0L, lastError);
        }

        private LeaseGrant toGrant() {
            return new LeaseGrant(taskId, taskType, payload, workerId, leaseToken, attemptCount,
                    createdAtMillis, visibleAtMillis, leaseExpiresAtMillis);
        }
    }
}
