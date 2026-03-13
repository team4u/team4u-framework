package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.*;
import com.team4u.framework.lease.model.*;
import lombok.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * 租赁后端内存实现
 * <p>
 * 该实现将所有任务数据存储在当前进程的内存中，不具备持久化能力。
 * 核心机制通过 {@link ConcurrentHashMap} 管理任务快照，确保存储层的并发访问安全。
 * 利用 {@link DelayQueue} 维护每组任务的可见性时间索引，实现高效的 worker 阻塞拉取和自动超时判定。
 * <p>
 * 适用场景：
 * 1. 单机环境下的轻量级异步任务处理。
 * 2. 自动化集成测试，用于快速验证调度逻辑。
 * 3. 对一致性要求不高但对性能要求极高的内存计算场景。
 */
public class InMemoryLeaseBackend implements LeaseBackend {

    /**
     * 任务快照存储，以任务 ID 为键，是系统状态的源头
     */
    private final ConcurrentMap<String, StoredTask> records = new ConcurrentHashMap<String, StoredTask>();
    /**
     * 任务组状态管理，每个任务组对应一个延迟队列，用于管理任务的可见时间
     */
    private final ConcurrentMap<String, DelayQueue<AvailabilityRef>> taskGroupStates = new ConcurrentHashMap<>();
    /**
     * 业务主键索引，用于通过业务唯一键快速定位任务 ID
     */
    private final ConcurrentMap<String, String> taskIdsByBusinessKey = new ConcurrentHashMap<>();

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 发布新任务
     * <p>
     * 该操作会创建一个新的任务记录并将其状态设置为 就绪。
     * 如果请求中指定了延迟时间，任务将在指定的延迟之后才对 worker 可见。
     *
     * @param request 任务发布请求信息
     * @return 生成的任务唯一 ID
     */
    @Override
    public synchronized String publish(LeasePublishRequest request) {
        validatePublishRequest(request);
        long now = System.currentTimeMillis();
        String taskId = nextTaskId();
        StoredTask task = new StoredTask(
                taskId,
                request.getTaskGroup(),
                request.getTaskType(),
                request.getPayload(),
                request.getBusinessKey(),
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

    /**
     * 存在性检查发布任务
     * <p>
     * 在发布任务前会检查是否存在具有相同业务主键的任务。
     * 如果业务主键已经存在，则不创建新任务，直接返回现有的任务详情。
     * 业务主键的作用范围局限在同一任务组内。
     *
     * @param request 任务发布请求信息
     * @return 发布结果，包含是否新创建的标志以及任务快照
     */
    @Override
    public synchronized LeasePublishResult publishIfAbsent(LeasePublishRequest request) {
        validatePublishRequest(request);
        if (isBlank(request.getBusinessKey())) {
            String taskId = publish(request);
            return LeasePublishResult.builder().created(true).taskId(taskId).record(get(taskId).orElse(null)).build();
        }
        String compositeKey = businessKey(request.getTaskGroup(), request.getBusinessKey());
        String existingTaskId = taskIdsByBusinessKey.get(compositeKey);
        if (existingTaskId != null) {
            return LeasePublishResult.builder()
                    .created(false)
                    .taskId(existingTaskId)
                    .record(get(existingTaskId).orElse(null))
                    .build();
        }
        String taskId = publish(request);
        taskIdsByBusinessKey.put(compositeKey, taskId);
        return LeasePublishResult.builder().created(true).taskId(taskId).record(get(taskId).orElse(null)).build();
    }

    /**
     * 领取任务租约
     * <p>
     * 该方法会阻塞当前线程，直到有符合订阅条件的任务变得可用，或者达到指定的等待超时时间。
     * 内部通过对 {@code wait/notifyAll} 的调用配合 {@link DelayQueue} 的状态观察来实现高效阻塞。
     * 成功领取任务后，任务状态将变更为 运行中，并绑定对应的 worker ID 和租约令牌。
     *
     * @param request 任务领取请求信息，包含订阅的服务组和期望的租约时长
     * @return 领取的租约凭证，如果在超时时间内没有可用任务，则返回 null
     * @throws InterruptedException 如果阻塞过程中线程被中断
     */
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
            // 遍历所有订阅的任务组，找出最近的一个即将可用的任务时间
            for (LeaseTaskGroupSubscription subscription : request.getSubscriptions()) {
                DelayQueue<AvailabilityRef> taskGroupQueue = taskGroupStates.get(subscription.getTaskGroup());
                if (taskGroupQueue == null) {
                    continue;
                }
                AvailabilityRef head = taskGroupQueue.peek();
                if (head != null) {
                    nextVisibleAt = Math.min(nextVisibleAt, head.getAvailableAtMillis());
                }
            }
            if (timeout == 0L) {
                return null;
            }
            long remaining = deadline - now;
            if (remaining <= 0L) {
                return null;
            }
            long waitMillis = remaining;
            // 如果存在尚未到期的任务，则根据任务的预计可见时间调整阻塞时长
            // DelayQueue 只负责唤醒时机，真正是否还能领取仍以 records 中的最新快照为准。
            if (nextVisibleAt != Long.MAX_VALUE) {
                waitMillis = Math.min(waitMillis, Math.max(1L, nextVisibleAt - now));
            }
            wait(waitMillis);
        }
    }

    /**
     * 关闭并完成任务
     * <p>
     * 由持有租约的 worker 调用，表示任务已经处理完毕。
     * 任务将进入 已关闭 状态，后续不再对外可见或可被操作。
     *
     * @param handle  当前持有的租约句柄
     * @param request 关闭请求信息，包含执行结果（成功/失败）等
     * @return 操作结果，若租约已失效或任务不存在则返回相应状态
     */
    @Override
    public synchronized LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
        StoredTask current = records.get(taskId(handle));
        LeaseRuntimeResult result = validateRuntimeMutation(current, handle);
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        store(current.close(request), false);
        return LeaseRuntimeResult.APPLIED;
    }

    /**
     * 续租心跳
     * <p>
     * 延长当前租约的有效期，防止任务因为超时而被重新分配。
     * 只能由当前持有有效租约的 worker 调用。
     *
     * @param handle       当前持有的租约句柄
     * @param extendMillis 期望延长的时长（从当前时间算起）
     * @return 操作结果
     */
    @Override
    public synchronized LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
        StoredTask current = records.get(taskId(handle));
        LeaseRuntimeResult result = validateRuntimeMutation(current, handle);
        if (result != LeaseRuntimeResult.APPLIED) {
            return result;
        }
        long nextLeaseExpiresAt = System.currentTimeMillis() + Math.max(1L, extendMillis);
        StoredTask next = current.heartbeat(nextLeaseExpiresAt);
        store(next, true);
        return LeaseRuntimeResult.APPLIED;
    }

    /**
     * 释放任务租约并重新入队
     * <p>
     * 当 worker 无法继续处理当前任务（例如发生临时异常）时，可以通过此方法放弃租约。
     * 该操作允许设置延迟时间，令任务在一段时间后才能重新被尝试执行。
     *
     * @param handle  当前持有的租约句柄
     * @param request 释放请求详情，包括可选的延迟、错误信息及附件
     * @return 操作结果
     */
    @Override
    public synchronized LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request) {
        StoredTask current = records.get(taskId(handle));
        LeaseRuntimeResult result = validateRuntimeMutation(current, handle);
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

    /**
     * 重新调度任务（管理接口）
     * <p>
     * 强制修改任务的可见时间，使其立即可用或推迟可用。
     * 只能对尚未关闭且当前未被锁定的任务执行此操作。
     *
     * @param taskId      任务 ID
     * @param delayMillis 延迟时长
     * @return 后台管理操作结果
     */
    @Override
    public synchronized LeaseAdminResult reschedule(String taskId, long delayMillis) {
        StoredTask current = records.get(taskId);
        LeaseAdminResult validation = validateAdminMutation(current);
        if (validation != LeaseAdminResult.APPLIED) {
            return validation;
        }
        long now = System.currentTimeMillis();
        StoredTask next = current.reschedule(now + Math.max(0L, delayMillis));
        store(next, true);
        return LeaseAdminResult.APPLIED;
    }

    /**
     * 修改任务状态为已关闭（管理接口）
     *
     * @param taskId  任务 ID
     * @param request 关闭详情
     * @return 后台管理操作结果
     */
    @Override
    public synchronized LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
        if (isBlank(taskId)) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        StoredTask current = records.get(taskId);
        LeaseAdminResult validation = validateAdminClose(current);
        if (validation != LeaseAdminResult.APPLIED) {
            return validation;
        }
        store(current.adminClose(request), false);
        return LeaseAdminResult.APPLIED;
    }

    /**
     * 重新调度已失败的任务（管理接口）
     * <p>
     * 针对已经进入失败关闭状态的任务，通过此方法可以将其重新启动进入就绪队列。
     *
     * @param taskId      任务 ID
     * @param delayMillis 延迟时长
     * @return 后台管理操作结果
     */
    @Override
    public synchronized LeaseAdminResult rescheduleFailed(String taskId, long delayMillis) {
        StoredTask current = records.get(taskId);
        if (current == null) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        if (!current.isFailedAndClosed()) {
            return LeaseAdminResult.CLOSED;
        }
        long visibleAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
        StoredTask next = current.reschedule(visibleAt);
        store(next, true);
        return LeaseAdminResult.APPLIED;
    }

    /**
     * 更新任务元数据（管理接口）
     *
     * @param request 更新请求，包含任务 payload、优先级、属性等
     * @return 后台管理操作结果
     */
    @Override
    public synchronized LeaseAdminResult update(LeaseUpdateRequest request) {
        StoredTask current = findTaskForUpdate(request);
        LeaseAdminResult validation = validateAdminMutation(current);
        if (validation != LeaseAdminResult.APPLIED) {
            return validation;
        }
        store(current.update(request), false);
        return LeaseAdminResult.APPLIED;
    }

    /**
     * 更新任务元数据并重调度（管理接口）
     *
     * @param request     更新请求
     * @param delayMillis 重新调度的延迟时长
     * @return 后台管理操作结果
     */
    @Override
    public synchronized LeaseAdminResult updateAndReschedule(LeaseUpdateRequest request, long delayMillis) {
        StoredTask current = findTaskForUpdate(request);
        LeaseAdminResult validation = validateAdminMutation(current);
        if (validation != LeaseAdminResult.APPLIED) {
            return validation;
        }
        long visibleAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
        store(current.update(request).reschedule(visibleAt), true);
        return LeaseAdminResult.APPLIED;
    }

    /**
     * 获取任务详细快照
     *
     * @param taskId 任务 ID
     * @return 任务记录的 Optional 封装
     */
    @Override
    public synchronized Optional<LeaseTaskRecord> get(String taskId) {
        StoredTask task = records.get(taskId);
        return task == null ? Optional.empty() : Optional.of(task.toRecord());
    }

    /**
     * 根据业务主键获取任务
     *
     * @param taskGroup   任务组
     * @param businessKey 业务唯一键
     * @return 任务记录的 Optional 封装
     */
    @Override
    public synchronized Optional<LeaseTaskRecord> getByBusinessKey(String taskGroup, String businessKey) {
        String taskId = taskIdsByBusinessKey.get(businessKey(taskGroup, businessKey));
        return taskId == null ? Optional.empty() : get(taskId);
    }

    /**
     * 分页列出符合条件的任务
     * <p>
     * 仅用于调试和管理后台展示。内存实现在列出任务时会先对内存中所有符合条件的记录进行全量排序再执行切片分页。
     *
     * @param request 查询请求参数，包括各种筛选维度
     * @return 分页后的任务列表
     */
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
        // 按照创建时间升序排列，创建时间相同时按 ID 分离冲突
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

    /**
     * 获取全量任务快照
     * <p>
     * 复制当前内存中的所有任务状态，主要用于持久化保存或导出统计分析。
     *
     * @return 任务快照映射表
     */
    public synchronized Map<String, StoredTask> snapshot() {
        Map<String, StoredTask> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, StoredTask> entry : records.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().toBuilder().build());
        }
        return snapshot;
    }

    /**
     * 尝试在指定时间内立即领取任务
     *
     * @param request 领取请求信息
     * @param now     当前基准时间（毫秒）
     * @return 若成功申领则返回租约凭证，否则返回 null
     */
    private LeaseGrant tryAcquire(LeaseAcquireRequest request, long now) {
        for (LeaseTaskGroupSubscription subscription : request.getSubscriptions()) {
            DelayQueue<AvailabilityRef> taskGroupQueue = taskGroupStates.get(subscription.getTaskGroup());
            if (taskGroupQueue == null) {
                continue;
            }
            while (true) {
                AvailabilityRef ref = taskGroupQueue.peek();
                // 如果队列头部没有元素，或者头部任务尚未到可执行时间，则该任务组当前不可领取
                if (ref == null || ref.getAvailableAtMillis() > now) {
                    break;
                }
                taskGroupQueue.poll();
                LeaseGrant grant = claim(ref, request.getWorkerId(), request.getLeaseMillis());
                if (grant != null) {
                    return grant;
                }
            }
        }
        return null;
    }

    /**
     * 执行具体的申领动作
     * <p>
     * 结合任务 ID 定位任务快照，并再次校验任务的真实可见性（因为索引中可能存在陈旧数据）。
     *
     * @param ref         可见性引用
     * @param workerId    准备领取的 worker ID
     * @param leaseMillis 期望租约时长
     * @return 成功的租约或空
     */
    private LeaseGrant claim(AvailabilityRef ref, String workerId, long leaseMillis) {
        StoredTask current = records.get(ref.taskId);
        if (current == null || current.isClose()) {
            return null;
        }
        long now = System.currentTimeMillis();
        // 任务分组索引里可能保留旧的可见性引用，因此领取前必须再和当前任务状态对齐一次。
        if (!current.isClaimable(ref.availableAtMillis, now)) {
            return null;
        }

        String leaseToken = nextLeaseToken();
        long leaseExpiresAt = now + Math.max(1L, leaseMillis);
        StoredTask leased = current.claim(workerId, leaseToken, leaseExpiresAt);
        store(leased, true);
        return leased.toGrant();
    }

    /**
     * 判断任务是否符合查询过滤条件
     */
    private boolean matches(LeaseQueryRequest request, StoredTask task) {
        if (request.getTaskGroup() != null && !request.getTaskGroup().equals(task.getTaskGroup())) {
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

    private StoredTask findTaskForUpdate(LeaseUpdateRequest request) {
        if (request == null || isBlank(request.getTaskId())) {
            return null;
        }
        return records.get(request.getTaskId());
    }

    /**
     * 校验运行时状态变更是否合法
     */
    private LeaseRuntimeResult validateRuntimeMutation(StoredTask current, LeaseHandle handle) {
        return current == null
                ? LeaseRuntimeResult.TASK_NOT_FOUND
                : current.validateRuntimeMutation(handle, System.currentTimeMillis());
    }

    /**
     * 校验管理层状态变更是否合法
     */
    private LeaseAdminResult validateAdminMutation(StoredTask current) {
        return current == null
                ? LeaseAdminResult.TASK_NOT_FOUND
                : current.validateAdminMutable(System.currentTimeMillis());
    }

    /**
     * 校验后台管理关闭操作是否合法
     */
    private LeaseAdminResult validateAdminClose(StoredTask current) {
        return current == null
                ? LeaseAdminResult.TASK_NOT_FOUND
                : current.validateAdminClose(System.currentTimeMillis());
    }

    private void validatePublishRequest(LeasePublishRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (isBlank(request.getTaskGroup())) {
            throw new IllegalArgumentException("request.taskGroup must not be blank");
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
        for (LeaseTaskGroupSubscription subscription : request.getSubscriptions()) {
            if (subscription == null || isBlank(subscription.getTaskGroup())) {
                throw new IllegalArgumentException("subscription.taskGroup must not be blank");
            }
        }
    }

    /**
     * 将任务引用投入延迟队列以供后续领取
     */
    private void offer(StoredTask task) {
        taskGroupState(task.getTaskGroup()).offer(new AvailabilityRef(
                task.getTaskId(), task.nextAvailableAt(), task.getPriority(), task.getCreatedAtMillis()));
    }

    /**
     * 持久化存储快照并触发订阅者唤醒
     *
     * @param task       待存储的任务快照
     * @param offerQueue 是否需要更新延迟队列索引
     */
    private void store(StoredTask task, boolean offerQueue) {
        // records 是状态单一真相源；DelayQueue 只是为了阻塞拉取，不要求严格删除旧引用。
        records.put(task.getTaskId(), task);
        if (!isBlank(task.getBusinessKey())) {
            taskIdsByBusinessKey.put(businessKey(task.getTaskGroup(), task.getBusinessKey()), task.getTaskId());
        }
        if (offerQueue) {
            offer(task);
        }
        // 唤醒当前正阻塞在 acquire 方法上的 worker 线程
        notifyAll();
    }

    private DelayQueue<AvailabilityRef> taskGroupState(String taskGroup) {
        return taskGroupStates.computeIfAbsent(taskGroup, ignored -> new DelayQueue<AvailabilityRef>());
    }

    private String nextTaskId() {
        return "lease-task-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String nextLeaseToken() {
        return "lease-token-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String businessKey(String taskGroup, String businessKey) {
        return taskGroup + "|" + businessKey;
    }

    /**
     * 校验租约句柄的完整性
     */
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

    /**
     * 任务可用性引用
     * <p>
     * 存储在延迟队列中，作为任务 ID、可见时间、优先级和创建时间的轻量级聚合，用于确定任务分配的先后顺序。
     */
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
            // 排序规则：可见时间早的任务优先；时间相同时优先级高的优先；优先级也相同时先创建的任务优先。
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

    /**
     * 内部存储的任务快照模型
     * <p>
     * 该类同时承载了任务的数据持有以及基于业务规则的状态转移逻辑。
     * 后端实现类仅负责并发编排和集合维护，具体的状态变迁细节由本类决定。
     */
    @Getter
    @Builder(toBuilder = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class StoredTask {
        private final String taskId;
        private final String taskGroup;
        private final String taskType;
        private final String payload;
        private final String businessKey;
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

        /**
         * 任务申领
         * <p>
         * 成功领取后进入 运行中 状态，并累加投递计次数。
         */
        private StoredTask claim(String workerId, String leaseToken, long leaseExpiresAtMillis) {
            return toBuilder()
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .state(LeaseTaskState.RUNNING)
                    .deliveryCount(deliveryCount + 1)
                    .build();
        }

        /**
         * 任务重新调度
         * <p>
         * 重置任务的可见时间，使其回归 就绪 状态，清除当前的租约持有信息。
         */
        private StoredTask reschedule(long visibleAtMillis) {
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

        /**
         * 任务租约释放
         */
        private StoredTask release(long visibleAtMillis, String payload, String errorMessage, Map<String, String> attributes) {
            StoredTask next = reschedule(visibleAtMillis).toBuilder()
                    .errorMessage(errorMessage)
                    .build();
            if (payload != null) {
                next = next.toBuilder().payload(payload).build();
            }
            if (attributes != null && !attributes.isEmpty()) {
                next = next.toBuilder().attributes(attributes).build();
            }
            return next;
        }

        /**
         * 续约操作
         */
        private StoredTask heartbeat(long leaseExpiresAtMillis) {
            return toBuilder()
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .leaseExpiresAtMillis(leaseExpiresAtMillis)
                    .state(LeaseTaskState.RUNNING)
                    .build();
        }

        /**
         * 运行时关闭任务
         */
        private StoredTask close(LeaseCloseRequest request) {
            LeaseCloseRequest safeRequest = request == null
                    ? LeaseCloseRequest.succeeded()
                    : request.normalizeForRuntime();
            return toClosedTask(safeRequest, false);
        }

        /**
         * 后台管理关闭操作
         */
        private StoredTask adminClose(LeaseCloseRequest request) {
            LeaseCloseRequest safeRequest = request == null
                    ? LeaseCloseRequest.cancelled(null)
                    : request.normalizeForAdmin();
            return toClosedTask(safeRequest, true);
        }

        /**
         * 更新任务基本信息
         */
        private StoredTask update(LeaseUpdateRequest request) {
            StoredTask.StoredTaskBuilder builder = toBuilder();
            if (!isBlank(request.getTaskType())) {
                builder.taskType(request.getTaskType());
            }
            if (request.getPayload() != null) {
                builder.payload(request.getPayload());
            }
            if (request.getPriority() != null) {
                builder.priority(request.getPriority());
            }
            if (request.getAttributes() != null && !request.getAttributes().isEmpty()) {
                builder.attributes(request.getAttributes());
            }
            return builder.build();
        }

        /**
         * 内部终态转换，计算最终的失败计数及错误码
         */
        private StoredTask toClosedTask(LeaseCloseRequest request, boolean adminOperation) {
            LeaseTaskOutcome outcome = request.getOutcome();
            int nextFailureCount = outcome == LeaseTaskOutcome.FAILED ? failureCount + 1 : failureCount;
            LeaseTaskFailureReason reason = request.getFailureReason();
            // 如果管理后台标记失败且未指定原因，默认赋予“人工关闭”原因
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

        private boolean isClose() {
            return state == LeaseTaskState.CLOSED;
        }

        private boolean isFailedAndClosed() {
            return state == LeaseTaskState.CLOSED && outcome == LeaseTaskOutcome.FAILED;
        }

        /**
         * 判断任务是否正在运行且租约尚未过期
         */
        private boolean hasActiveLease(long now) {
            return state == LeaseTaskState.RUNNING && leaseExpiresAtMillis >= now;
        }

        /**
         * 计算下一次应当变为可见的时间点
         */
        private long nextAvailableAt() {
            return state == LeaseTaskState.RUNNING ? leaseExpiresAtMillis : visibleAtMillis;
        }

        /**
         * 校验在特定的可见时间戳下，当前任务快照是否真正可被领取。
         *
         * @param expectedAvailableAt 索引中记录的期望可见时间
         * @param now                 系统当前时间
         */
        private boolean isClaimable(long expectedAvailableAt, long now) {
            long availableAt = nextAvailableAt();
            return availableAt == expectedAvailableAt && availableAt <= now;
        }

        /**
         * 校验 worker 的运行时权限
         */
        private LeaseRuntimeResult validateRuntimeMutation(LeaseHandle handle, long now) {
            // 运行时操作（如续约、完成）必须由当前明确持有有效租约的特定 worker 发起。
            if (isClose()) {
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

        /**
         * 校验后台管理层元数据修改权限
         */
        private LeaseAdminResult validateAdminMutable(long now) {
            // 管理层操作不允许强行覆盖已经终态或正在且有效运行的任务快照，以防止逻辑交叉感染。
            if (isClose()) {
                return LeaseAdminResult.CLOSED;
            }
            if (hasActiveLease(now)) {
                return LeaseAdminResult.ACTIVE_LEASE_PRESENT;
            }
            return LeaseAdminResult.APPLIED;
        }

        /**
         * 校验后台管理层直接关闭权限
         */
        private LeaseAdminResult validateAdminClose(long now) {
            if (isClose()) {
                return LeaseAdminResult.CLOSED;
            }
            if (hasActiveLease(now)) {
                return LeaseAdminResult.ACTIVE_LEASE_PRESENT;
            }
            return LeaseAdminResult.APPLIED;
        }

        /**
         * 转换为租约凭证模型
         */
        private LeaseGrant toGrant() {
            return LeaseGrant.builder()
                    .taskId(taskId)
                    .workerId(workerId)
                    .leaseToken(leaseToken)
                    .taskGroup(taskGroup)
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

        /**
         * 转换为只读记录模型
         */
        private LeaseTaskRecord toRecord() {
            return LeaseTaskRecord.builder()
                    .taskId(taskId)
                    .taskGroup(taskGroup)
                    .taskType(taskType)
                    .payload(payload)
                    .businessKey(businessKey)
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
