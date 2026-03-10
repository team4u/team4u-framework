package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.*;
import com.team4u.framework.lease.model.*;
import lombok.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * 内存版租赁后端实现
 * <p>
 * 该实现将所有任务状态存储在 JVM 内存中，不具备持久化能力。
 * 内部通过 {@link ConcurrentHashMap} 管理任务快照，并利用 {@link DelayQueue} 实现任务的可视化延迟判定及
 * Worker 的阻塞拉取。
 * 主要适用于：
 * 1. 单机环境下的简单任务调度。
 * 2. 自动化集成测试场景。
 */
public class InMemoryLeaseBackend implements LeaseBackend {

    private final ConcurrentMap<String, StoredTask> records = new ConcurrentHashMap<String, StoredTask>();
    private final ConcurrentMap<QueueKey, DelayQueue<AvailabilityRef>> queueStates = new ConcurrentHashMap<QueueKey, DelayQueue<AvailabilityRef>>();

    @Override
    public synchronized String publish(LeasePublishRequest request) {
        validatePublishRequest(request);
        long now = System.currentTimeMillis();
        String taskId = nextTaskId();
        StoredTask task = new StoredTask(
                taskId,
                request.getQueue(),
                request.getTaskType(),
                request.getPayload(),
                now,
                now + Math.max(0L, request.getDelayMillis()),
                request.getPriority(),
                0,
                0,
                request.getAttributes(),
                LeaseTaskState.READY,
                null,
                null,
                null,
                null,
                0L,
                null);
        store(task, true);
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
                DelayQueue<AvailabilityRef> queue = queueStates.get(new QueueKey(subscription.getQueue()));
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
            // DelayQueue 只负责唤醒时机，真正是否还能领取仍以 records 中的最新快照为准。
            if (nextVisibleAt != Long.MAX_VALUE) {
                waitMillis = Math.min(waitMillis, Math.max(1L, nextVisibleAt - now));
            }
            wait(waitMillis);
        }
    }

    @Override
    public synchronized LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
        StoredTask current = records.get(taskId(handle));
        LeaseRuntimeResult result = current == null
                ? LeaseRuntimeResult.TASK_NOT_FOUND
                : current.validateRuntimeMutation(handle, System.currentTimeMillis());
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        store(current.close(request), false);
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public synchronized LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
        StoredTask current = records.get(taskId(handle));
        LeaseRuntimeResult result = current == null
                ? LeaseRuntimeResult.TASK_NOT_FOUND
                : current.validateRuntimeMutation(handle, System.currentTimeMillis());
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        long nextLeaseExpiresAt = System.currentTimeMillis() + Math.max(1L, extendMillis);
        StoredTask next = current.heartbeat(nextLeaseExpiresAt);
        store(next, true);
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public synchronized LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request) {
        StoredTask current = records.get(taskId(handle));
        LeaseRuntimeResult result = current == null
                ? LeaseRuntimeResult.TASK_NOT_FOUND
                : current.validateRuntimeMutation(handle, System.currentTimeMillis());
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        long visibleAt = System.currentTimeMillis() + Math.max(0L, request.getDelayMillis());
        StoredTask next = current.release(
                visibleAt,
                request.getPayload(),
                request.getErrorMessage(),
                request.getAttributes());
        store(next, true);
        return LeaseRuntimeResult.APPLIED;
    }

    @Override
    public synchronized LeaseAdminResult reschedule(String taskId, long delayMillis) {
        StoredTask current = records.get(taskId);
        LeaseAdminResult validation = current == null
                ? LeaseAdminResult.TASK_NOT_FOUND
                : current.validateAdminMutable(System.currentTimeMillis());
        if (validation != LeaseAdminResult.APPLIED) {
            return validation;
        }
        long now = System.currentTimeMillis();
        StoredTask next = current.reschedule(now + Math.max(0L, delayMillis));
        store(next, true);
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public synchronized LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
        if (isBlank(taskId)) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        StoredTask current = records.get(taskId);
        LeaseAdminResult validation;
        if (current == null) {
            validation = LeaseAdminResult.TASK_NOT_FOUND;
        } else if (current.getState() == LeaseTaskState.RUNNING && current.hasActiveLease(System.currentTimeMillis())) {
            validation = LeaseAdminResult.ACTIVE_LEASE_PRESENT;
        } else if (current.getState() == LeaseTaskState.CLOSED) {
            validation = LeaseAdminResult.CLOSED;
        } else {
            validation = LeaseAdminResult.APPLIED;
        }
        if (validation != LeaseAdminResult.APPLIED) {
            return validation;
        }
        store(current.adminClose(request), false);
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public synchronized LeaseAdminResult requeueFailed(String taskId, long delayMillis) {
        StoredTask current = records.get(taskId);
        if (current == null) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        if (!current.isRequeueableFailure()) {
            return LeaseAdminResult.CLOSED;
        }
        long visibleAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
        StoredTask next = current.reschedule(visibleAt);
        store(next, true);
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public synchronized LeaseAdminResult update(LeaseUpdateRequest request) {
        if (request == null || isBlank(request.getTaskId())) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        StoredTask current = records.get(request.getTaskId());
        if (current == null) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        LeaseAdminResult validation = current.validateAdminMutable(System.currentTimeMillis());
        if (validation != LeaseAdminResult.APPLIED) {
            return validation;
        }
        StoredTask.StoredTaskBuilder builder = current.toBuilder();
        if (!isBlank(request.getTaskType())) {
            builder.taskType(request.getTaskType());
        }
        if (request.getPayload() != null) {
            builder.payload(request.getPayload());
        }
        if (request.getPriority() != null) {
            builder.priority(request.getPriority());
        }
        if (request.getAttributes() != null) {
            builder.attributes(request.getAttributes());
        }
        store(builder.build(), false);
        return LeaseAdminResult.APPLIED;
    }

    @Override
    public synchronized Optional<LeaseTaskRecord> get(String taskId) {
        StoredTask task = records.get(taskId);
        return task == null ? Optional.empty() : Optional.of(task.toRecord());
    }

    @Override
    public synchronized LeaseTaskPage list(LeaseQueryRequest request) {
        LeaseQueryRequest safeRequest = request == null ? LeaseQueryRequest.builder().build() : request;
        List<LeaseTaskRecord> matches = new ArrayList<>();
        for (StoredTask task : records.values()) {
            if (!matches(safeRequest, task)) {
                continue;
            }
            matches.add(task.toRecord());
        }
        matches.sort(Comparator.comparingLong(LeaseTaskRecord::getCreatedAtMillis)
                .thenComparing(LeaseTaskRecord::getTaskId));
        int page = Math.max(0, safeRequest.getPage());
        int pageSize = safeRequest.getPageSize() <= 0 ? 50 : safeRequest.getPageSize();
        int fromIndex = Math.min(matches.size(), page * pageSize);
        int toIndex = Math.min(matches.size(), fromIndex + pageSize);
        return LeaseTaskPage.builder()
                .total(matches.size())
                .page(page)
                .pageSize(pageSize)
                .items(new ArrayList<>(matches.subList(fromIndex, toIndex)))
                .build();
    }

    public synchronized Map<String, StoredTask> snapshot() {
        Map<String, StoredTask> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, StoredTask> entry : records.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().toBuilder().build());
        }
        return snapshot;
    }

    private LeaseGrant tryAcquire(LeaseAcquireRequest request, long now) {
        for (LeaseSubscription subscription : request.getSubscriptions()) {
            DelayQueue<AvailabilityRef> queue = queueStates.get(new QueueKey(subscription.getQueue()));
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
        if (current == null || current.isTerminal()) {
            return null;
        }
        long now = System.currentTimeMillis();
        // 队列里可能保留旧的可见性引用，因此领取前必须再和当前任务状态对齐一次。
        if (!current.isClaimable(ref.availableAtMillis, now)) {
            return null;
        }

        String leaseToken = nextLeaseToken();
        long leaseExpiresAt = now + Math.max(1L, leaseMillis);
        StoredTask leased = current.claim(workerId, leaseToken, leaseExpiresAt);
        store(leased, true);
        return leased.toGrant();
    }

    private boolean matches(LeaseQueryRequest request, StoredTask task) {
        if (request.getQueue() != null && !request.getQueue().equals(task.getQueue())) {
            return false;
        }
        if (request.getTaskType() != null && !request.getTaskType().equals(task.getTaskType())) {
            return false;
        }
        if (!request.getStates().isEmpty() && !request.getStates().contains(task.getState())) {
            return false;
        }
        if (!request.getOutcomes().isEmpty() && !request.getOutcomes().contains(task.getOutcome())) {
            return false;
        }
        if (!request.getFailureReasons().isEmpty()
                && !request.getFailureReasons().contains(task.getFailureReason())) {
            return false;
        }
        return request.getWorkerId() == null || request.getWorkerId().equals(task.getWorkerId());
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
        queueState(task.getQueue()).offer(new AvailabilityRef(
                task.getTaskId(), task.nextAvailableAt(), task.getPriority(), task.getCreatedAtMillis()));
    }

    private void store(StoredTask task, boolean offerQueue) {
        // records 是状态单一真相源；DelayQueue 只是为了阻塞拉取，不要求严格删除旧引用。
        records.put(task.getTaskId(), task);
        if (offerQueue) {
            offer(task);
        }
        notifyAll();
    }

    private DelayQueue<AvailabilityRef> queueState(String queue) {
        return queueStates.computeIfAbsent(new QueueKey(queue),
                ignored -> new DelayQueue<AvailabilityRef>());
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

    private void validateHandle(LeaseHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        if (isBlank(handle.getTaskId())) {
            throw new IllegalArgumentException("handle.taskId must not be blank");
        }
        if (isBlank(handle.getWorkerId())) {
            throw new IllegalArgumentException("handle.workerId must not be blank");
        }
        if (isBlank(handle.getLeaseToken())) {
            throw new IllegalArgumentException("handle.leaseToken must not be blank");
        }
    }

    private String taskId(LeaseHandle handle) {
        validateHandle(handle);
        return handle.getTaskId();
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
            return Objects.equals(queue, that.queue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(queue);
        }
    }

    @Getter
    @Builder(toBuilder = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class StoredTask {
        // 该模型同时承载任务快照和状态机规则，backend 只负责并发与存储编排。
        private final String taskId;
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
        private final LeaseTaskState state;
        private final LeaseTaskOutcome outcome;
        private final LeaseTaskFailureReason failureReason;
        private final String workerId;
        private final String leaseToken;
        private final long leaseExpiresAtMillis;
        private final String errorMessage;

        private StoredTask claim(String workerId, String leaseToken, long leaseExpiresAtMillis) {
            // 成功领取后进入 RUNNING，并累计投递次数。
            return toBuilder()
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .state(LeaseTaskState.RUNNING)
                    .deliveryCount(deliveryCount + 1)
                    .build();
        }

        private StoredTask reschedule(long visibleAtMillis) {
            // 重新入队会清掉租约和关闭结果，但保留失败计数。
            return toBuilder()
                    .visibleAtMillis(visibleAtMillis)
                    .state(LeaseTaskState.READY)
                    .outcome(null)
                    .failureReason(null)
                    .errorMessage(null)
                    .workerId(null)
                    .leaseToken(null)
                    .leaseExpiresAtMillis(0L)
                    .build();
        }

        private StoredTask release(long visibleAtMillis, String payload, String errorMessage, Map<String, String> attributes) {
            StoredTask next = reschedule(visibleAtMillis).toBuilder()
                    .errorMessage(errorMessage)
                    .build();
            if (payload != null) {
                next = next.toBuilder().payload(payload).build();
            }
            if (attributes != null) {
                next = next.toBuilder().attributes(attributes).build();
            }
            return next;
        }

        private StoredTask heartbeat(long leaseExpiresAtMillis) {
            // heartbeat 只能续租，不应改变投递次数或失败信息。
            return toBuilder()
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .state(LeaseTaskState.RUNNING)
                    .build();
        }

        private StoredTask close(LeaseCloseRequest request) {
            LeaseCloseRequest safeRequest = request == null
                    ? LeaseCloseRequest.succeeded()
                    : request.normalizeForRuntime();
            return toClosedTask(safeRequest, false);
        }

        private StoredTask adminClose(LeaseCloseRequest request) {
            LeaseCloseRequest safeRequest = request == null
                    ? LeaseCloseRequest.cancelled(null)
                    : request.normalizeForAdmin();
            return toClosedTask(safeRequest, true);
        }

        private StoredTask toClosedTask(LeaseCloseRequest request, boolean adminOperation) {
            LeaseTaskOutcome outcome = request.getOutcome();
            int nextFailureCount = outcome == LeaseTaskOutcome.FAILED ? failureCount + 1 : failureCount;
            LeaseTaskFailureReason reason = request.getFailureReason();
            if (adminOperation && outcome == LeaseTaskOutcome.FAILED && reason == null) {
                reason = LeaseTaskFailureReason.MANUAL_FAIL;
            }
            return toBuilder()
                    .state(LeaseTaskState.CLOSED)
                    .outcome(outcome)
                    .failureReason(outcome == LeaseTaskOutcome.FAILED ? reason : null)
                    .failureCount(nextFailureCount)
                    .errorMessage(request.getErrorMessage())
                    .payload(request.getPayload() == null ? payload : request.getPayload())
                    .attributes(request.getAttributes().isEmpty() ? attributes : request.getAttributes())
                    .workerId(null)
                    .leaseToken(null)
                    .leaseExpiresAtMillis(0L)
                    .build();
        }

        private boolean isTerminal() {
            return state == LeaseTaskState.CLOSED;
        }

        private boolean isRequeueableFailure() {
            return state == LeaseTaskState.CLOSED && outcome == LeaseTaskOutcome.FAILED;
        }

        private boolean hasActiveLease(long now) {
            return state == LeaseTaskState.RUNNING && leaseExpiresAtMillis >= now;
        }

        private long nextAvailableAt() {
            return state == LeaseTaskState.RUNNING ? leaseExpiresAtMillis : visibleAtMillis;
        }

        private boolean isClaimable(long expectedAvailableAt, long now) {
            long availableAt = nextAvailableAt();
            return availableAt == expectedAvailableAt && availableAt <= now;
        }

        private LeaseRuntimeResult validateRuntimeMutation(LeaseHandle handle, long now) {
            // runtime 操作必须由当前持有有效租约的 worker 发起。
            if (isTerminal()) {
                return LeaseRuntimeResult.CLOSED;
            }
            if (state != LeaseTaskState.RUNNING) {
                return LeaseRuntimeResult.LEASE_LOST;
            }
            if (!Objects.equals(workerId, handle.getWorkerId())
                    || !Objects.equals(leaseToken, handle.getLeaseToken())) {
                return LeaseRuntimeResult.LEASE_LOST;
            }
            if (leaseExpiresAtMillis < now) {
                return LeaseRuntimeResult.LEASE_LOST;
            }
            return LeaseRuntimeResult.APPLIED;
        }

        private LeaseAdminResult validateAdminMutable(long now) {
            // 管理操作不能覆盖终态任务，也不能打断仍有效的租约。
            if (isTerminal()) {
                return LeaseAdminResult.CLOSED;
            }
            if (hasActiveLease(now)) {
                return LeaseAdminResult.ACTIVE_LEASE_PRESENT;
            }
            return LeaseAdminResult.APPLIED;
        }

        private LeaseGrant toGrant() {
            return LeaseGrant.builder()
                    .taskId(taskId)
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .queue(queue)
                    .taskType(taskType)
                    .payload(payload)
                    .deliveryCount(deliveryCount)
                    .failureCount(failureCount)
                    .attributes(attributes)
                    .createdAtMillis(createdAtMillis)
                    .visibleAtMillis(visibleAtMillis)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .build();
        }

        private LeaseTaskRecord toRecord() {
            return LeaseTaskRecord.builder()
                    .taskId(taskId)
                    .queue(queue)
                    .taskType(taskType)
                    .payload(payload)
                    .state(state)
                    .outcome(outcome)
                    .failureReason(failureReason)
                    .workerId(workerId)
                    .priority(priority)
                    .deliveryCount(deliveryCount)
                    .failureCount(failureCount)
                    .createdAtMillis(createdAtMillis)
                    .visibleAtMillis(visibleAtMillis)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .errorMessage(errorMessage)
                    .attributes(attributes == null ? Collections.emptyMap() : attributes)
                    .build();
        }
    }
}
