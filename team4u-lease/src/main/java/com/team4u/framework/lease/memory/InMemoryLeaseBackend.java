package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeaseGrant;
import com.team4u.framework.lease.LeaseTaskStatus;
import lombok.*;

import java.util.*;
import java.util.concurrent.*;

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
                Collections.emptyMap(),
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
        records.put(taskId, current.withTerminal(LeaseTaskStatus.SUCCEEDED, null));
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
        long nextLeaseExpiresAt = System.currentTimeMillis() + Math.max(1L, extendMillis);
        StoredTask next = current.withLease(workerId, leaseToken, nextLeaseExpiresAt);
        records.put(taskId, next);
        queue.offer(new AvailabilityRef(taskId, next.getLeaseExpiresAtMillis()));
    }

    public synchronized Map<String, StoredTask> snapshot() {
        Map<String, StoredTask> snapshot = new LinkedHashMap<String, StoredTask>();
        for (Map.Entry<String, StoredTask> entry : records.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().toBuilder().build());
        }
        return snapshot;
    }

    /**
     * 等待并获取队列中可见的任务。
     *
     * @param deadline          获取操作的绝对截止毫秒时间戳
     * @param waitTimeoutMillis 相对等待时长
     * @return 可用的任务引用；若在截止时间内无任务则返回 null
     * @throws InterruptedException 若当前线程被中断
     */
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

    /**
     * 竞争并锁定任务的所有权（租约）。
     * <p>
     * 该方法会进行“可见性校验”和“版本校验”，确保一个过期任务或被重新调度的任务不会被错误领取。
     *
     * @param ref         从延迟队列中取出的任务可见性引用
     * @param workerId    尝试竞争的 Worker ID
     * @param leaseMillis 期望锁定的时长
     * @return 成功竞争后返回租约通行证；若任务已被取消、完成或可见性已变更则返回 null
     */
    private synchronized LeaseGrant claim(AvailabilityRef ref, String workerId, long leaseMillis) {
        StoredTask current = records.get(ref.taskId);
        // 如果任务已被删除或处于终态，则标记无效
        if (current == null || isTerminal(current)) {
            return null;
        }

        long now = System.currentTimeMillis();
        // 计算任务最新的可用时间（可能是原始可见时间，也可能是之前租约的到期时间）
        long availableAt = current.getStatus() == LeaseTaskStatus.LEASED
                ? current.getLeaseExpiresAtMillis()
                : current.getVisibleAtMillis();

        // 双重检查：如果任务的可用时刻已经发生了偏移，当前 ref 已失效
        if (availableAt != ref.availableAtMillis || availableAt > now) {
            return null;
        }

        String leaseToken = nextLeaseToken();
        long leaseExpiresAt = now + Math.max(1L, leaseMillis);

        // 更新记录并维护内存视图
        StoredTask leased = current.claim(workerId, leaseToken, leaseExpiresAt);
        records.put(ref.taskId, leased);

        // 重新放入延迟队列，以便租约到期后能再次可见
        queue.offer(new AvailabilityRef(ref.taskId, leaseExpiresAt));
        return leased.toGrant();
    }

    /**
     * 校验租约的合法性。
     *
     * @return true 表示该 Worker 仍合法持有该任务的当前租约且未过期
     */
    private boolean matchesLease(StoredTask current, String workerId, String leaseToken) {
        if (current == null || current.getStatus() != LeaseTaskStatus.LEASED) {
            return false;
        }
        if (!stringEquals(current.getWorkerId(), workerId) || !stringEquals(current.getLeaseToken(), leaseToken)) {
            return false;
        }
        // 只有当前时间小于到期时间才认为有效
        return current.getLeaseExpiresAtMillis() >= System.currentTimeMillis();
    }

    /**
     * 判断任务是否已进入最终状态（成功或死亡）。
     */
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
        return Objects.equals(left, right);
    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static class AvailabilityRef implements Delayed {
        private final String taskId;
        private final long availableAtMillis;

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
     * 存储的任务实例，用于内存中维护任务状态和生命周期。
     * 提供不可变的数据视图以及通过 Lombok 生成的辅助修改方法。
     */
    @Getter
    @Builder(toBuilder = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class StoredTask {
        /**
         * 任务唯一标识符
         */
        private final String taskId;
        /**
         * 任务类型，用于区分不同业务逻辑的处理器
         */
        private final String taskType;
        /**
         * 任务执行所需的数据载荷
         */
        private final String payload;
        /**
         * 任务创建的时间戳（毫秒）
         */
        private final long createdAtMillis;
        /**
         * 任务预计对 Worker 可见的时间戳（毫秒），用于延迟调度
         */

        private final long visibleAtMillis;
        /**
         * 任务已经被尝试执行的次数
         */
        private final int attemptCount;
        /**
         * 任务的附加属性集合
         */
        @Singular
        private final Map<String, String> attributes;
        /**
         * 任务当前所处的状态（如：已调度、已租赁、已成功、已死亡等）
         */

        private final LeaseTaskStatus status;
        /**
         * 当前持有该任务租约的 Worker ID
         */

        private final String workerId;
        /**
         * 验证租约所有权的令牌
         */

        private final String leaseToken;
        /**
         * 当前租约到期的时间戳（毫秒）
         */

        private final long leaseExpiresAtMillis;
        /**
         * 任务最近一次执行失败的错误信息
         */

        private final String lastError;

        /**
         * 认领任务并更新相关租约信息。
         *
         * @param workerId             认领任务的 Worker ID
         * @param leaseToken           分配给此次租约的令牌
         * @param leaseExpiresAtMillis 租约的过期时间戳
         * @return 认领后的新任务状态副本
         */
        private StoredTask claim(String workerId, String leaseToken, long leaseExpiresAtMillis) {
            return toBuilder()
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .status(LeaseTaskStatus.LEASED)
                    .attemptCount(attemptCount + 1)
                    .build();
        }

        /**
         * 重新调度任务，清除当前的租约信息并设定新的可见时间。
         *
         * @param visibleAtMillis 任务下一次变为可见的时间戳
         * @param lastError       重新调度前记录的错误信息（如果有）
         * @return 重新调度后的新任务状态副本
         */
        private StoredTask withSchedule(long visibleAtMillis, String lastError) {
            return toBuilder()
                    .visibleAtMillis(visibleAtMillis)
                    .lastError(lastError)
                    .status(LeaseTaskStatus.SCHEDULED)
                    .workerId(null)
                    .leaseToken(null)
                    .leaseExpiresAtMillis(0L)
                    .build();
        }

        /**
         * 更新任务的租约信息（如续租）。
         *
         * @param workerId             持有租约的 Worker ID
         * @param leaseToken           租约令牌
         * @param leaseExpiresAtMillis 新的租约过期时间戳
         * @return 更新租约后的新任务状态副本
         */
        private StoredTask withLease(String workerId, String leaseToken, long leaseExpiresAtMillis) {
            return toBuilder()
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .status(LeaseTaskStatus.LEASED)
                    .build();
        }

        /**
         * 将任务设置为终态（如：成功或死亡），并清除租约信息。
         *
         * @param status    目标终态
         * @param lastError 导致终态的错误信息（如有）
         * @return 设置为终态后的新任务状态副本
         */
        private StoredTask withTerminal(LeaseTaskStatus status, String lastError) {
            return toBuilder()
                    .status(status)
                    .lastError(lastError)
                    .workerId(null)
                    .leaseToken(null)
                    .leaseExpiresAtMillis(0L)
                    .build();
        }

        /**
         * 将存储的任务对象转换为对外提供的租约授权模型。
         *
         * @return 对应的 {@link LeaseGrant} 对象
         */
        private LeaseGrant toGrant() {
            return LeaseGrant.builder()
                    .taskId(taskId)
                    .taskType(taskType)
                    .payload(payload)
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .attemptCount(attemptCount)
                    .createdAtMillis(createdAtMillis)
                    .visibleAtMillis(visibleAtMillis)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .build();
        }
    }
}
