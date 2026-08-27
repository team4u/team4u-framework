package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.api.TaskPage;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.LeaseTimes;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.RetryCommand;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import com.team4u.framework.lease.spi.UpdateCommand;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-local lease backend. A single lock guards all state transitions, so task mutations and
 * their queue indexes are committed atomically.
 */
public final class InMemoryLeaseBackend implements LeaseBackend {

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ConcurrentMap<String, TaskRecord> tasksById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TaskRecord> tasksByDedupKey = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CandidateSet>> candidates = new HashMap<>();
    private long idSequence;

    public InMemoryLeaseBackend() {
        this(Clock.systemUTC());
    }

    public InMemoryLeaseBackend(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public SubmitResult submit(SubmitCommand command) {
        lock.lock();
        try {
            TaskRecord created = new TaskRecord(command);
            String dedupIndexKey = dedupIndexKey(created.queue, created.type, created.dedupKey);
            if (dedupIndexKey != null) {
                TaskRecord existing = tasksByDedupKey.get(dedupIndexKey);
                if (existing != null) {
                    return SubmitResult.of(existing.taskId, false, existing.snapshot());
                }
            }

            created.taskId = nextTaskId();
            tasksById.put(created.taskId, created);
            if (dedupIndexKey != null) {
                tasksByDedupKey.put(dedupIndexKey, created);
            }
            index(created);
            available.signalAll();
            return SubmitResult.of(created.taskId, true, created.snapshot());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public LeaseGrant acquire(AcquireCommand command) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            TaskRecord claimed = claimOne(command.getSubscription(), command.getWorkerId(),
                    command.getLeaseMillis());
            if (claimed == null) {
                return null;
            }
            return LeaseGrant.of(LeaseHandle.of(claimed.taskId, claimed.workerId,
                    claimed.leaseToken), claimed.snapshot());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
        if (extendMillis <= 0L) {
            throw new IllegalArgumentException("extendMillis must be positive");
        }
        lock.lock();
        try {
            TaskRecord task = tasksById.get(handle.getTaskId());
            RuntimeFence fence = runtimeFence(task, handle);
            if (fence.result != null) {
                return fence.result;
            }
            long leaseExpiresAt = LeaseTimes.plusMillis(nowMillis(), extendMillis);
            task.leaseExpiresAtMillis = Math.max(task.leaseExpiresAtMillis, leaseExpiresAt);
            return RuntimeResult.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RuntimeResult close(LeaseHandle handle, LeaseCompletion completion) {
        lock.lock();
        try {
            TaskRecord task = tasksById.get(handle.getTaskId());
            RuntimeFence fence = runtimeFence(task, handle);
            if (fence.result != null) {
                return fence.result;
            }
            task.status = completion.getStatus();
            task.payload = patchValue(completion.getPayload(), task.payload);
            task.errorMessage = completion.getErrorMessage();
            if (completion.hasAttributes()) {
                task.attributes = completion.getAttributes();
            }
            clearLease(task);
            removeFromIndex(task);
            return RuntimeResult.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RuntimeResult release(LeaseHandle handle, LeaseRetry retry) {
        lock.lock();
        try {
            TaskRecord task = tasksById.get(handle.getTaskId());
            RuntimeFence fence = runtimeFence(task, handle);
            if (fence.result != null) {
                return fence.result;
            }
            long visibleAt = LeaseTimes.plusMillis(nowMillis(), retry.getDelayMillis());
            task.status = TaskStatus.PENDING;
            task.visibleAtMillis = visibleAt;
            task.payload = patchValue(retry.getPayload(), task.payload);
            task.errorMessage = retry.getErrorMessage();
            if (retry.hasAttributes()) {
                task.attributes = retry.getAttributes();
            }
            clearLease(task);
            reindex(task);
            available.signalAll();
            return RuntimeResult.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AdminResult complete(AdminCompletionCommand command) {
        lock.lock();
        try {
            AdminTask task = adminTask(command.getQueue(), command.getTaskId());
            if (task.result != null) {
                return task.result;
            }
            TaskRecord record = task.record;
            LeaseCompletion completion = command.getCompletion();
            record.status = completion.getStatus();
            record.payload = patchValue(completion.getPayload(), record.payload);
            record.errorMessage = completion.getErrorMessage();
            if (completion.hasAttributes()) {
                record.attributes = completion.getAttributes();
            }
            clearLease(record);
            removeFromIndex(record);
            return AdminResult.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AdminResult reschedule(RescheduleCommand command) {
        lock.lock();
        try {
            AdminTask task = adminTask(command.getQueue(), command.getTaskId());
            if (task.result != null) {
                return task.result;
            }
            TaskRecord record = task.record;
            long visibleAt = LeaseTimes.plusMillis(nowMillis(), command.getDelayMillis());
            record.visibleAtMillis = visibleAt;
            record.status = TaskStatus.PENDING;
            record.errorMessage = null;
            clearLease(record);
            reindex(record);
            available.signalAll();
            return AdminResult.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AdminResult retry(RetryCommand command) {
        lock.lock();
        try {
            TaskRecord record = tasksById.get(command.getTaskId());
            if (record == null || !record.queue.equals(command.getQueue())) {
                return AdminResult.TASK_NOT_FOUND;
            }
            if (record.status == TaskStatus.RUNNING
                    && nowMillis() < record.leaseExpiresAtMillis) {
                return AdminResult.ACTIVE_LEASE_PRESENT;
            }
            if (record.status != TaskStatus.FAILED) {
                return AdminResult.TERMINAL;
            }

            long visibleAt = LeaseTimes.plusMillis(nowMillis(), command.getDelayMillis());
            record.status = TaskStatus.PENDING;
            record.visibleAtMillis = visibleAt;
            record.errorMessage = null;
            clearLease(record);
            reindex(record);
            available.signalAll();
            return AdminResult.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AdminResult update(UpdateCommand command) {
        lock.lock();
        try {
            AdminTask task = adminTask(command.getQueue(), command.getTaskId());
            if (task.result != null) {
                return task.result;
            }
            applyUpdate(task.record, command, false);
            return AdminResult.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AdminResult updateAndReschedule(UpdateCommand command) {
        lock.lock();
        try {
            AdminTask task = adminTask(command.getQueue(), command.getTaskId());
            if (task.result != null) {
                return task.result;
            }
            applyUpdate(task.record, command, true);
            available.signalAll();
            return AdminResult.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<TaskSnapshot> get(String queue, String taskId) {
        TaskRecord task = tasksById.get(taskId);
        if (task == null || !task.queue.equals(queue)) {
            return Optional.empty();
        }
        lock.lock();
        try {
            return Optional.of(task.snapshot());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<TaskSnapshot> getByDeduplicationKey(String queue, String taskType, String key) {
        TaskRecord task = tasksByDedupKey.get(dedupIndexKey(queue, taskType, key));
        if (task == null || !task.queue.equals(queue)) {
            return Optional.empty();
        }
        lock.lock();
        try {
            return Optional.of(task.snapshot());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TaskPage list(String queue, TaskQuery query) {
        lock.lock();
        try {
            List<TaskSnapshot> matches = new ArrayList<TaskSnapshot>();
            for (TaskRecord task : tasksById.values()) {
                if (!task.queue.equals(queue)
                        || query.getType() != null && !task.type.equals(query.getType())
                        || query.getStatus() != null && task.status != query.getStatus()
                        || query.getWorkerId() != null && !Objects.equals(task.workerId,
                                query.getWorkerId())) {
                    continue;
                }
                matches.add(task.snapshot());
            }
            Collections.sort(matches, SNAPSHOTS_BY_CREATION);
            int from = (int) Math.min(matches.size(), (long) query.getPage() * query.getPageSize());
            int to = Math.min(matches.size(), from + query.getPageSize());
            return TaskPage.of(matches.subList(from, to), query.getPage(), query.getPageSize(),
                    matches.size());
        } finally {
            lock.unlock();
        }
    }

    private TaskRecord claimOne(TaskSubscription subscription, String workerId, long leaseMillis) {
        long now = nowMillis();
        long leaseExpiresAt = LeaseTimes.plusMillis(now, leaseMillis);
        Map<String, CandidateSet> byType = candidates.get(subscription.getQueue());
        if (byType == null) {
            return null;
        }
        TaskRecord selected = null;
        for (String type : subscription.getTaskTypes()) {
            CandidateSet set = byType.get(type);
            if (set == null) {
                continue;
            }
            TaskRecord task = firstEligible(set, now);
            if (task != null && (selected == null
                    || CANDIDATES.compare(new Candidate(task), new Candidate(selected)) < 0)) {
                selected = task;
            }
        }
        if (selected == null) {
            return null;
        }

        selected.status = TaskStatus.RUNNING;
        selected.workerId = workerId;
        selected.leaseToken = UUID.randomUUID().toString();
        selected.leaseExpiresAtMillis = leaseExpiresAt;
        selected.attemptCount++;
        reindex(selected);
        return selected;
    }

    private TaskRecord firstEligible(CandidateSet set, long now) {
        Iterator<Candidate> iterator = set.tasks.iterator();
        while (iterator.hasNext()) {
            Candidate candidate = iterator.next();
            TaskRecord task = tasksById.get(candidate.taskId);
            if (task == null || task.status.isTerminal()) {
                iterator.remove();
                set.byId.remove(candidate.taskId);
                continue;
            }
            if (isAvailable(task, now)) {
                return task;
            }
        }
        return null;
    }

    private boolean isAvailable(TaskRecord task, long now) {
        if (task.status == TaskStatus.RUNNING) {
            return now >= task.leaseExpiresAtMillis;
        }
        return task.status == TaskStatus.PENDING && now >= task.visibleAtMillis;
    }


    private RuntimeFence runtimeFence(TaskRecord task, LeaseHandle handle) {
        if (task == null) {
            return new RuntimeFence(null, RuntimeResult.TASK_NOT_FOUND);
        }
        if (task.status.isTerminal()) {
            return new RuntimeFence(task, RuntimeResult.TERMINAL);
        }
        if (task.status != TaskStatus.RUNNING
                || !Objects.equals(task.workerId, handle.getWorkerId())
                || !Objects.equals(task.leaseToken, handle.getLeaseToken())
                || nowMillis() >= task.leaseExpiresAtMillis) {
            return new RuntimeFence(task, RuntimeResult.LEASE_LOST);
        }
        return new RuntimeFence(task, null);
    }

    private AdminTask adminTask(String queue, String taskId) {
        TaskRecord task = tasksById.get(taskId);
        if (task == null || !task.queue.equals(queue)) {
            return new AdminTask(null, AdminResult.TASK_NOT_FOUND);
        }
        if (task.status.isTerminal()) {
            return new AdminTask(task, AdminResult.TERMINAL);
        }
        if (task.status == TaskStatus.RUNNING && nowMillis() < task.leaseExpiresAtMillis) {
            return new AdminTask(task, AdminResult.ACTIVE_LEASE_PRESENT);
        }
        return new AdminTask(task, null);
    }

    private void applyUpdate(TaskRecord task, UpdateCommand command, boolean reschedule) {
        String previousType = task.type;
        String nextType = command.getTaskType() == null ? previousType : command.getTaskType();
        String oldDedupIndexKey = dedupIndexKey(task.queue, previousType, task.dedupKey);
        String newDedupIndexKey = dedupIndexKey(task.queue, nextType, task.dedupKey);
        requireAvailableDedupIndexKey(newDedupIndexKey, task.taskId);
        long nextVisibleAt = reschedule
                ? LeaseTimes.plusMillis(nowMillis(), command.getDelayMillis() == null
                        ? 0L : command.getDelayMillis().longValue())
                : task.visibleAtMillis;

        task.type = nextType;
        if (command.getPayload() != null) {
            task.payload = command.getPayload();
        }
        if (command.getPriority() != null) {
            task.priority = command.getPriority().intValue();
        }
        if (command.hasAttributes()) {
            task.attributes = command.getAttributes();
        }
        removeFromIndex(task.queue, previousType, task.taskId);

        if (reschedule) {
            task.visibleAtMillis = nextVisibleAt;
            clearLease(task);
            task.errorMessage = null;
            task.status = TaskStatus.PENDING;
        }
        index(task);

        if (!Objects.equals(oldDedupIndexKey, newDedupIndexKey)) {
            if (oldDedupIndexKey != null) {
                tasksByDedupKey.remove(oldDedupIndexKey);
            }
            if (newDedupIndexKey != null) {
                tasksByDedupKey.put(newDedupIndexKey, task);
            }
        }
    }

    private void requireAvailableDedupIndexKey(String dedupIndexKey, String taskId) {
        if (dedupIndexKey == null) {
            return;
        }
        TaskRecord owner = tasksByDedupKey.get(dedupIndexKey);
        if (owner != null && !owner.taskId.equals(taskId)) {
            throw new IllegalStateException(
                    "dedup key is already owned by another task: " + dedupIndexKey);
        }
    }


    private void index(TaskRecord task) {
        typeIndex(task.queue, task.type).add(new Candidate(task));
    }

    private void reindex(TaskRecord task) {
        removeFromIndex(task);
        index(task);
    }

    private void removeFromIndex(TaskRecord task) {
        removeFromIndex(task.queue, task.type, task.taskId);
    }

    private void removeFromIndex(String queue, String type, String taskId) {
        CandidateSet set = candidates.get(queue) == null ? null
                : candidates.get(queue).get(type);
        if (set != null) {
            set.removeById(taskId);
        }
    }

    private CandidateSet typeIndex(String queue, String type) {
        Map<String, CandidateSet> byType = candidates.get(queue);
        if (byType == null) {
            byType = new HashMap<String, CandidateSet>();
            candidates.put(queue, byType);
        }
        CandidateSet set = byType.get(type);
        if (set == null) {
            set = new CandidateSet();
            byType.put(type, set);
        }
        return set;
    }

    private String dedupIndexKey(String queue, String taskType, String key) {
        return key == null ? null : queue + '\u0000' + taskType + '\u0000' + key;
    }

    private long nowMillis() {
        return clock.millis();
    }

    private String nextTaskId() {
        return "task-" + ++idSequence + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String patchValue(String next, String current) {
        return next == null ? current : next;
    }

    private static void clearLease(TaskRecord task) {
        task.workerId = null;
        task.leaseToken = null;
        task.leaseExpiresAtMillis = 0L;
    }

    private static final Comparator<TaskSnapshot> SNAPSHOTS_BY_CREATION =
            new Comparator<TaskSnapshot>() {
                @Override
                public int compare(TaskSnapshot left, TaskSnapshot right) {
                    int byCreated = left.getCreatedAt().compareTo(right.getCreatedAt());
                    return byCreated != 0 ? byCreated : left.getTaskId().compareTo(right.getTaskId());
                }
            };

    private static final Comparator<Candidate> CANDIDATES = new Comparator<Candidate>() {
        @Override
        public int compare(Candidate left, Candidate right) {
            int byPriority = Integer.compare(right.priority, left.priority);
            if (byPriority != 0) {
                return byPriority;
            }
            int byCreated = Long.compare(left.createdAtMillis, right.createdAtMillis);
            return byCreated != 0 ? byCreated : left.taskId.compareTo(right.taskId);
        }
    };

    private final class TaskRecord {
        private String taskId;
        private final String queue;
        private String type;
        private String payload;
        private final String dedupKey;
        private TaskStatus status = TaskStatus.PENDING;
        private String workerId;
        private String leaseToken;
        private int priority;
        private int attemptCount;
        private final long createdAtMillis;
        private long visibleAtMillis;
        private long leaseExpiresAtMillis;
        private String errorMessage;
        private Map<String, String> attributes = Collections.emptyMap();

        private TaskRecord(SubmitCommand command) {
            this.queue = command.getQueue();
            this.type = command.getTaskType();
            this.payload = command.getPayload();
            this.dedupKey = command.getDeduplicationKey();
            this.priority = command.getPriority();
            this.attributes = command.getAttributes();
            this.createdAtMillis = nowMillis();
            this.visibleAtMillis = LeaseTimes.plusMillis(createdAtMillis, command.getDelayMillis());
        }

        private TaskSnapshot snapshot() {
            return TaskSnapshot.builder()
                    .taskId(taskId)
                    .queue(queue)
                    .type(type)
                    .payload(payload)
                    .dedupKey(dedupKey)
                    .status(status)
                    .workerId(workerId)
                    .priority(priority)
                    .attemptCount(attemptCount)
                    .createdAt(Instant.ofEpochMilli(createdAtMillis))
                    .visibleAt(Instant.ofEpochMilli(visibleAtMillis))
                    .leaseExpiresAt(status == TaskStatus.RUNNING
                            ? Instant.ofEpochMilli(leaseExpiresAtMillis) : null)
                    .errorMessage(errorMessage)
                    .attributes(attributes)
                    .build();
        }
    }

    private static final class Candidate {
        private final String taskId;
        private final int priority;
        private final long createdAtMillis;

        private Candidate(TaskRecord task) {
            this.taskId = task.taskId;
            this.priority = task.priority;
            this.createdAtMillis = task.createdAtMillis;
        }
    }

    private final class CandidateSet {
        private final NavigableSet<Candidate> tasks = new TreeSet<>(CANDIDATES);
        private final Map<String, Candidate> byId = new HashMap<String, Candidate>();

        private void add(Candidate candidate) {
            replace(candidate);
        }

        private void replace(Candidate candidate) {
            Candidate old = byId.put(candidate.taskId, candidate);
            if (old != null) {
                tasks.remove(old);
            }
            tasks.add(candidate);
        }

        private void removeById(String taskId) {
            Candidate candidate = byId.remove(taskId);
            if (candidate != null) {
                tasks.remove(candidate);
            }
        }
    }

    private static final class RuntimeFence {
        private final TaskRecord record;
        private final RuntimeResult result;

        private RuntimeFence(TaskRecord record, RuntimeResult result) {
            this.record = record;
            this.result = result;
        }
    }

    private static final class AdminTask {
        private final TaskRecord record;
        private final AdminResult result;

        private AdminTask(TaskRecord record, AdminResult result) {
            this.record = record;
            this.result = result;
        }
    }
}
