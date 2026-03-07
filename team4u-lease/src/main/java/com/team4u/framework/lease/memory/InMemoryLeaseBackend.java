package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.LeaseAcquireRequest;
import com.team4u.framework.lease.LeaseAdminResult;
import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeaseGrant;
import com.team4u.framework.lease.LeasePublishRequest;
import com.team4u.framework.lease.LeaseQueryRequest;
import com.team4u.framework.lease.LeaseRuntimeResult;
import com.team4u.framework.lease.LeaseSubscription;
import com.team4u.framework.lease.LeaseTaskPage;
import com.team4u.framework.lease.LeaseTaskRecord;
import com.team4u.framework.lease.LeaseTaskStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 内存版租约后端。
 */
public class InMemoryLeaseBackend implements LeaseBackend {

    private final ConcurrentMap<String, StoredTask> records = new ConcurrentHashMap<String, StoredTask>();
    private final ConcurrentMap<QueueKey, DelayQueue<AvailabilityRef>> queueStates =
            new ConcurrentHashMap<QueueKey, DelayQueue<AvailabilityRef>>();

    @Override
    public synchronized String publish(LeasePublishRequest request) {
        validatePublishRequest(request);
        long now = System.currentTimeMillis();
        String taskId = nextTaskId();
        StoredTask task = new StoredTask(
                taskId,
                defaultNamespace(request.getNamespace()),
                request.getQueue(),
                request.getTaskType(),
                request.getPayload(),
                now,
                now + Math.max(0L, request.getDelayMillis()),
                request.getPriority(),
                0,
                0,
                request.getAttributes(),
                LeaseTaskStatus.SCHEDULED,
                null,
                null,
                0L,
                null
        );
        records.put(taskId, task);
        offer(task);
        notifyAll();
        return taskId;
    }

    @Override
    public synchronized LeaseGrant acquire(LeaseAcquireRequest request) throws InterruptedException {
        validateAcquireRequest(request);
        long timeout = Math.max(0L, request.getWaitTimeoutMillis());
        long deadline = System.currentTimeMillis() + timeout;
        while (true) {
            long now = System.currentTimeMillis();
            long nextVisibleAt = Long.MAX_VALUE;
            LeaseGrant grant = tryAcquire(request, now);
            if (grant != null) {
                return grant;
            }
            for (LeaseSubscription subscription : request.getSubscriptions()) {
                DelayQueue<AvailabilityRef> queue = queueStates.get(new QueueKey(
                        defaultNamespace(subscription.getNamespace()), subscription.getQueue()));
                if (queue == null) {
                    continue;
                }
                AvailabilityRef head = queue.peek();
                if (head != null) {
                    nextVisibleAt = Math.min(nextVisibleAt, head.getAvailableAtMillis());
                }
            }
            if (timeout <= 0L) {
                return null;
            }
            long remaining = deadline - now;
            if (remaining <= 0L) {
                return null;
            }
            long waitMillis = remaining;
            if (nextVisibleAt != Long.MAX_VALUE) {
                waitMillis = Math.min(waitMillis, Math.max(1L, nextVisibleAt - now));
            }
            wait(waitMillis);
        }
    }

    @Override
    public synchronized LeaseRuntimeResult ack(String taskId, String workerId, String leaseToken) {
        StoredTask current = records.get(taskId);
        LeaseRuntimeResult result = validateRuntimeMutation(current, workerId, leaseToken);
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        records.put(taskId, current.withTerminal(LeaseTaskStatus.SUCCEEDED, current.getFailureCount(), null));
        notifyAll();
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public synchronized LeaseRuntimeResult retry(String taskId, String workerId, String leaseToken,
                                                 long delayMillis, Throwable cause) {
        StoredTask current = records.get(taskId);
        LeaseRuntimeResult result = validateRuntimeMutation(current, workerId, leaseToken);
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        long visibleAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
        StoredTask next = current.withSchedule(visibleAt, current.getFailureCount() + 1, errorMessage(cause));
        records.put(taskId, next);
        offer(next);
        notifyAll();
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public synchronized LeaseRuntimeResult fail(String taskId, String workerId, String leaseToken, Throwable cause) {
        StoredTask current = records.get(taskId);
        LeaseRuntimeResult result = validateRuntimeMutation(current, workerId, leaseToken);
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        records.put(taskId, current.withTerminal(LeaseTaskStatus.DEAD, current.getFailureCount() + 1, errorMessage(cause)));
        notifyAll();
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public synchronized LeaseRuntimeResult heartbeat(String taskId, String workerId, String leaseToken,
                                                     long extendMillis) {
        StoredTask current = records.get(taskId);
        LeaseRuntimeResult result = validateRuntimeMutation(current, workerId, leaseToken);
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        long nextLeaseExpiresAt = System.currentTimeMillis() + Math.max(1L, extendMillis);
        StoredTask next = current.withLease(workerId, leaseToken, nextLeaseExpiresAt);
        records.put(taskId, next);
        offer(next);
        notifyAll();
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public synchronized LeaseAdminResult reschedule(String taskId, long delayMillis) {
        StoredTask current = records.get(taskId);
        if (current == null) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        if (isTerminal(current)) {
            return LeaseAdminResult.TERMINAL;
        }
        if (hasActiveLease(current)) {
            return LeaseAdminResult.ACTIVE_LEASE_PRESENT;
        }
        long now = System.currentTimeMillis();
        StoredTask next = current.withSchedule(now + Math.max(0L, delayMillis), current.getFailureCount(), current.getLastError());
        records.put(taskId, next);
        offer(next);
        notifyAll();
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public synchronized LeaseAdminResult cancel(String taskId) {
        StoredTask current = records.get(taskId);
        if (current == null) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        if (isTerminal(current)) {
            return LeaseAdminResult.TERMINAL;
        }
        if (hasActiveLease(current)) {
            return LeaseAdminResult.ACTIVE_LEASE_PRESENT;
        }
        records.put(taskId, current.withTerminal(LeaseTaskStatus.DEAD, current.getFailureCount(), "cancelled"));
        notifyAll();
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public synchronized LeaseAdminResult requeueDead(String taskId, long delayMillis) {
        StoredTask current = records.get(taskId);
        if (current == null) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        if (current.getStatus() != LeaseTaskStatus.DEAD) {
            return isTerminal(current) ? LeaseAdminResult.TERMINAL : LeaseAdminResult.TERMINAL;
        }
        long visibleAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
        StoredTask next = current.withSchedule(visibleAt, current.getFailureCount(), current.getLastError());
        records.put(taskId, next);
        offer(next);
        notifyAll();
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public synchronized Optional<LeaseTaskRecord> get(String taskId) {
        StoredTask task = records.get(taskId);
        return task == null ? Optional.<LeaseTaskRecord>empty() : Optional.of(task.toRecord());
    }

    @Override
    public synchronized LeaseTaskPage list(LeaseQueryRequest request) {
        LeaseQueryRequest safeRequest = request == null ? LeaseQueryRequest.builder().build() : request;
        List<LeaseTaskRecord> matches = new ArrayList<LeaseTaskRecord>();
        for (StoredTask task : records.values()) {
            if (!matches(safeRequest, task)) {
                continue;
            }
            matches.add(task.toRecord());
        }
        matches.sort(Comparator.comparingLong(LeaseTaskRecord::getCreatedAtMillis).thenComparing(LeaseTaskRecord::getTaskId));
        int page = Math.max(0, safeRequest.getPage());
        int pageSize = safeRequest.getPageSize() <= 0 ? 50 : safeRequest.getPageSize();
        int fromIndex = Math.min(matches.size(), page * pageSize);
        int toIndex = Math.min(matches.size(), fromIndex + pageSize);
        return LeaseTaskPage.builder()
                .total(matches.size())
                .page(page)
                .pageSize(pageSize)
                .items(new ArrayList<LeaseTaskRecord>(matches.subList(fromIndex, toIndex)))
                .build();
    }

    public synchronized Map<String, StoredTask> snapshot() {
        Map<String, StoredTask> snapshot = new LinkedHashMap<String, StoredTask>();
        for (Map.Entry<String, StoredTask> entry : records.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().toBuilder().build());
        }
        return snapshot;
    }

    private LeaseGrant tryAcquire(LeaseAcquireRequest request, long now) {
        for (LeaseSubscription subscription : request.getSubscriptions()) {
            DelayQueue<AvailabilityRef> queue = queueStates.get(new QueueKey(
                    defaultNamespace(subscription.getNamespace()), subscription.getQueue()));
            if (queue == null) {
                continue;
            }
            while (true) {
                AvailabilityRef ref = queue.peek();
                if (ref == null || ref.getAvailableAtMillis() > now) {
                    break;
                }
                queue.poll();
                LeaseGrant grant = claim(ref, request.getWorkerId(), request.getLeaseMillis());
                if (grant != null) {
                    return grant;
                }
            }
        }
        return null;
    }

    private LeaseGrant claim(AvailabilityRef ref, String workerId, long leaseMillis) {
        StoredTask current = records.get(ref.taskId);
        if (current == null || isTerminal(current)) {
            return null;
        }
        long now = System.currentTimeMillis();
        long availableAt = nextAvailableAt(current);
        if (availableAt != ref.availableAtMillis || availableAt > now) {
            return null;
        }

        String leaseToken = nextLeaseToken();
        long leaseExpiresAt = now + Math.max(1L, leaseMillis);
        StoredTask leased = current.claim(workerId, leaseToken, leaseExpiresAt);
        records.put(ref.taskId, leased);
        offer(leased);
        notifyAll();
        return leased.toGrant();
    }

    private boolean matches(LeaseQueryRequest request, StoredTask task) {
        if (request.getNamespace() != null && !request.getNamespace().equals(task.getNamespace())) {
            return false;
        }
        if (request.getQueue() != null && !request.getQueue().equals(task.getQueue())) {
            return false;
        }
        if (request.getTaskType() != null && !request.getTaskType().equals(task.getTaskType())) {
            return false;
        }
        if (!request.getStatuses().isEmpty() && !request.getStatuses().contains(task.getStatus())) {
            return false;
        }
        return request.getWorkerId() == null || request.getWorkerId().equals(task.getWorkerId());
    }

    private LeaseRuntimeResult validateRuntimeMutation(StoredTask current, String workerId, String leaseToken) {
        if (current == null) {
            return LeaseRuntimeResult.TASK_NOT_FOUND;
        }
        if (isTerminal(current)) {
            return LeaseRuntimeResult.TERMINAL;
        }
        if (current.getStatus() != LeaseTaskStatus.LEASED) {
            return LeaseRuntimeResult.LEASE_LOST;
        }
        if (!Objects.equals(current.getWorkerId(), workerId) || !Objects.equals(current.getLeaseToken(), leaseToken)) {
            return LeaseRuntimeResult.LEASE_LOST;
        }
        if (current.getLeaseExpiresAtMillis() < System.currentTimeMillis()) {
            return LeaseRuntimeResult.LEASE_LOST;
        }
        return LeaseRuntimeResult.APPLIED;
    }

    private boolean hasActiveLease(StoredTask current) {
        return current.getStatus() == LeaseTaskStatus.LEASED
                && current.getLeaseExpiresAtMillis() >= System.currentTimeMillis();
    }

    private boolean isTerminal(StoredTask current) {
        return current.getStatus() == LeaseTaskStatus.SUCCEEDED || current.getStatus() == LeaseTaskStatus.DEAD;
    }

    private void validatePublishRequest(LeasePublishRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (isBlank(request.getQueue())) {
            throw new IllegalArgumentException("request.queue must not be blank");
        }
        if (isBlank(request.getTaskType())) {
            throw new IllegalArgumentException("request.taskType must not be blank");
        }
    }

    private void validateAcquireRequest(LeaseAcquireRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (isBlank(request.getWorkerId())) {
            throw new IllegalArgumentException("request.workerId must not be blank");
        }
        if (request.getLeaseMillis() <= 0L) {
            throw new IllegalArgumentException("request.leaseMillis must be greater than 0");
        }
        if (request.getSubscriptions().isEmpty()) {
            throw new IllegalArgumentException("request.subscriptions must not be empty");
        }
        for (LeaseSubscription subscription : request.getSubscriptions()) {
            if (subscription == null || isBlank(subscription.getQueue())) {
                throw new IllegalArgumentException("subscription.queue must not be blank");
            }
        }
    }

    private void offer(StoredTask task) {
        queueState(task.getNamespace(), task.getQueue()).offer(new AvailabilityRef(
                task.getTaskId(), nextAvailableAt(task), task.getPriority(), task.getCreatedAtMillis()));
    }

    private long nextAvailableAt(StoredTask task) {
        return task.getStatus() == LeaseTaskStatus.LEASED ? task.getLeaseExpiresAtMillis() : task.getVisibleAtMillis();
    }

    private DelayQueue<AvailabilityRef> queueState(String namespace, String queue) {
        return queueStates.computeIfAbsent(new QueueKey(defaultNamespace(namespace), queue),
                ignored -> new DelayQueue<AvailabilityRef>());
    }

    private String defaultNamespace(String namespace) {
        return isBlank(namespace) ? "default" : namespace;
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static class AvailabilityRef implements Delayed {
        private final String taskId;
        private final long availableAtMillis;
        private final int priority;
        private final long createdAtMillis;

        @Override
        public long getDelay(TimeUnit unit) {
            long diff = availableAtMillis - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            AvailabilityRef that = (AvailabilityRef) other;
            int byTime = Long.compare(this.availableAtMillis, that.availableAtMillis);
            if (byTime != 0) {
                return byTime;
            }
            int byPriority = Integer.compare(that.priority, this.priority);
            if (byPriority != 0) {
                return byPriority;
            }
            return Long.compare(this.createdAtMillis, that.createdAtMillis);
        }
    }

    @AllArgsConstructor
    private static class QueueKey {
        private final String namespace;
        private final String queue;

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof QueueKey)) {
                return false;
            }
            QueueKey that = (QueueKey) obj;
            return Objects.equals(namespace, that.namespace) && Objects.equals(queue, that.queue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(namespace, queue);
        }
    }

    @Getter
    @Builder(toBuilder = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class StoredTask {
        private final String taskId;
        private final String namespace;
        private final String queue;
        private final String taskType;
        private final String payload;
        private final long createdAtMillis;
        private final long visibleAtMillis;
        private final int priority;
        private final int deliveryCount;
        private final int failureCount;
        @Singular
        private final Map<String, String> attributes;
        private final LeaseTaskStatus status;
        private final String workerId;
        private final String leaseToken;
        private final long leaseExpiresAtMillis;
        private final String lastError;

        private StoredTask claim(String workerId, String leaseToken, long leaseExpiresAtMillis) {
            return toBuilder()
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .status(LeaseTaskStatus.LEASED)
                    .deliveryCount(deliveryCount + 1)
                    .build();
        }

        private StoredTask withSchedule(long visibleAtMillis, int failureCount, String lastError) {
            return toBuilder()
                    .visibleAtMillis(visibleAtMillis)
                    .failureCount(failureCount)
                    .lastError(lastError)
                    .status(LeaseTaskStatus.SCHEDULED)
                    .workerId(null)
                    .leaseToken(null)
                    .leaseExpiresAtMillis(0L)
                    .build();
        }

        private StoredTask withLease(String workerId, String leaseToken, long leaseExpiresAtMillis) {
            return toBuilder()
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .status(LeaseTaskStatus.LEASED)
                    .build();
        }

        private StoredTask withTerminal(LeaseTaskStatus status, int failureCount, String lastError) {
            return toBuilder()
                    .status(status)
                    .failureCount(failureCount)
                    .lastError(lastError)
                    .workerId(null)
                    .leaseToken(null)
                    .leaseExpiresAtMillis(0L)
                    .build();
        }

        private LeaseGrant toGrant() {
            return new LeaseGrant(taskId, queue, taskType, payload, deliveryCount, failureCount, attributes,
                    createdAtMillis, visibleAtMillis, leaseExpiresAtMillis, workerId, leaseToken);
        }

        private LeaseTaskRecord toRecord() {
            return LeaseTaskRecord.builder()
                    .taskId(taskId)
                    .namespace(namespace)
                    .queue(queue)
                    .taskType(taskType)
                    .payload(payload)
                    .status(status)
                    .workerId(workerId)
                    .priority(priority)
                    .deliveryCount(deliveryCount)
                    .failureCount(failureCount)
                    .createdAtMillis(createdAtMillis)
                    .visibleAtMillis(visibleAtMillis)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .lastError(lastError)
                    .attributes(attributes == null ? Collections.<String, String>emptyMap() : attributes)
                    .build();
        }
    }
}
