package com.team4u.framework.lease.jdbc;

import com.team4u.framework.base.util.Assert;
import com.team4u.framework.base.util.IdUtil;
import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.jdbc.codec.LeaseJsonCodec;
import com.team4u.framework.lease.jdbc.dialect.LeaseDbDialect;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import com.team4u.framework.lease.model.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 基于 JDBC 的任务租赁后端实现
 * <p>
 * 该实现通过关系型数据库持久化任务状态。其核心特性包括：
 * 1. <b>抢占逻辑：</b>使用了基于 SQL 的分块拉取及乐观锁更新机制，确保分布式环境下任务不被重复消费。
 * 2. <b>事务保障：</b>所有状态流转操作均依赖数据库的事务和行级控制。
 * 3. <b>管理功能：</b>支持通过数据库进行的分页查询、任务重排及人工干预操作。
 *
 * @author jay.wu
 */
public class JdbcLeaseBackend implements LeaseBackend {

    private static final int ACQUIRE_BATCH_SIZE = 10;
    private static final long DEFAULT_WAIT_POLL_MILLIS = 50L;

    private final JdbcLeaseTaskDao dao;
    private final LongSupplier clock;

    /**
     * 使用数据源初始化后端，默认使用 MySQL 方言和系统时钟
     *
     * @param dataSource 数据源
     */
    public JdbcLeaseBackend(DataSource dataSource) {
        this(dataSource, new MySqlLeaseDbDialect(), System::currentTimeMillis);
    }

    /**
     * 使用数据源和指定的数据库方言初始化后端，默认使用系统时钟
     *
     * @param dataSource 数据源
     * @param dialect    数据库方言
     */
    public JdbcLeaseBackend(DataSource dataSource, LeaseDbDialect dialect) {
        this(dataSource, dialect, System::currentTimeMillis);
    }

    /**
     * 内部构造函数，支持注入自定义时钟，主要用于单元测试
     *
     * @param dataSource 数据源
     * @param dialect    数据库方言
     * @param clock      时钟供应商
     */
    JdbcLeaseBackend(DataSource dataSource, LeaseDbDialect dialect, LongSupplier clock) {
        this.dao = new JdbcLeaseTaskDao(dataSource, dialect, new LeaseJsonCodec());
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 发布一个新任务
     *
     * @param request 发布请求
     * @return 生成的任务 ID
     */
    @Override
    public String publish(LeasePublishRequest request) {
        validatePublishRequest(request);
        LeaseTaskEntity entity = newPublishedEntity(request);
        insert(entity);
        return entity.getTaskId();
    }

    /**
     * 如果任务不存在则发布新任务（基于业务键去重）
     *
     * @param request 发布请求
     * @return 发布结果，包含是否创建成功及任务 ID
     */
    @Override
    public LeasePublishResult publishIfAbsent(LeasePublishRequest request) {
        validatePublishRequest(request);
        LeaseTaskEntity entity = newPublishedEntity(request);
        try {
            dao.insert(entity);
            return LeasePublishResult.builder()
                    .created(true)
                    .taskId(entity.getTaskId())
                    .record(entity.toRecord())
                    .build();
        } catch (SQLException e) {
            if (StringUtil.isBlank(request.getBusinessKey()) || !isDuplicateKey(e)) {
                throw new IllegalStateException("publishIfAbsent failed", e);
            }
            try {
                LeaseTaskEntity existing = dao.findByBusinessKey(request.getTaskGroup(), request.getBusinessKey());
                if (existing != null) {
                    return LeasePublishResult.builder()
                            .created(false)
                            .taskId(existing.getTaskId())
                            .record(existing.toRecord())
                            .build();
                }
            } catch (SQLException ignored) {
                // fall through and surface original failure
            }
            throw new IllegalStateException("publishIfAbsent failed", e);
        }
    }

    /**
     * 实现租约抢占逻辑
     * <p>
     * 在指定的等待时间内周期性尝试获取任务。
     *
     * @param request 抢占请求
     * @return 成功获取的租约授权，超时未获则返回 null
     * @throws InterruptedException 在阻塞休眠期间被中断
     */
    @Override
    public LeaseGrant acquire(LeaseAcquireRequest request) throws InterruptedException {
        validateAcquireRequest(request);
        long timeout = Math.max(0L, request.getWaitTimeoutMillis());
        long deadline = now() + timeout;
        while (true) {
            LeaseGrant grant = tryAcquireOnce(request);
            if (grant != null) {
                return grant;
            }
            if (timeout == 0L) {
                return null;
            }
            long current = now();
            if (current >= deadline) {
                return null;
            }
            Thread.sleep(Math.min(DEFAULT_WAIT_POLL_MILLIS, deadline - current));
        }
    }

    /**
     * 关闭并完成运行中的任务
     *
     * @param handle  租约句柄
     * @param request 关闭请求，包含执行状态和结果
     * @return 运行态变动结果
     */
    @Override
    public LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
        return applyRuntimeMutation(handle,
                now -> dao.close(handle.getTaskId(), handle.getWorkerId(), handle.getLeaseToken(), request, now));
    }

    /**
     * 续延租约（心跳）
     *
     * @param handle       租约句柄
     * @param extendMillis 续延的毫秒数
     * @return 运行态变动结果
     */
    @Override
    public LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
        return applyRuntimeMutation(handle, now -> dao.heartbeat(
                handle.getTaskId(),
                handle.getWorkerId(),
                handle.getLeaseToken(),
                now + Math.max(1L, extendMillis),
                now));
    }

    /**
     * 释放当前持有的租约并让任务重新入队，可延迟可见
     *
     * @param handle  租约句柄
     * @param request 释放请求，包含延迟时间和属性更新
     * @return 运行态变动结果
     */
    @Override
    public LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request) {
        return applyRuntimeMutation(handle, now -> dao.release(
                handle.getTaskId(),
                handle.getWorkerId(),
                handle.getLeaseToken(),
                now + Math.max(0L, request.getDelayMillis()),
                request.getPayload(),
                request.getAttributes(),
                request.getErrorMessage(),
                now));
    }

    /**
     * 管理面：重新调度任务
     *
     * @param taskId      任务 ID
     * @param delayMillis 调度延迟毫秒数
     * @return 管理态操作结果
     */
    @Override
    public LeaseAdminResult reschedule(String taskId, long delayMillis) {
        return applyAdminMutation(taskId, now -> dao.reschedule(taskId, now + Math.max(0L, delayMillis), now));
    }

    /**
     * 管理面：强制关闭任务
     *
     * @param taskId  任务 ID
     * @param request 关闭请求
     * @return 管理态操作结果
     */
    @Override
    public LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
        return applyAdminMutation(taskId, now -> dao.close(taskId, request, now));
    }

    /**
     * 管理面：将已关闭的失败任务重新放入队列执行
     *
     * @param taskId      任务 ID
     * @param delayMillis 调度延迟毫秒数
     * @return 管理态操作结果
     */
    @Override
    public LeaseAdminResult rescheduleFailed(String taskId, long delayMillis) {
        validateTaskId(taskId);
        try {
            LeaseTaskEntity current = dao.findById(taskId);
            if (current == null) {
                return LeaseAdminResult.TASK_NOT_FOUND;
            }
            if (current.getState() != LeaseTaskState.CLOSED || current.getOutcome() != LeaseTaskOutcome.FAILED) {
                return LeaseAdminResult.CLOSED;
            }
            long now = now();
            return dao.rescheduleFailed(taskId, now + Math.max(0L, delayMillis), now) == 1
                    ? LeaseAdminResult.APPLIED
                    : LeaseAdminResult.CLOSED;
        } catch (SQLException e) {
            throw new IllegalStateException("rescheduleFailed failed: " + taskId, e);
        }
    }

    /**
     * 获取指定任务的记录信息
     *
     * @param taskId 任务 ID
     * @return 任务记录
     */
    @Override
    public Optional<LeaseTaskRecord> get(String taskId) {
        validateTaskId(taskId);
        try {
            LeaseTaskEntity entity = dao.findById(taskId);
            return entity == null ? Optional.empty() : Optional.of(entity.toRecord());
        } catch (SQLException e) {
            throw new IllegalStateException("get failed: " + taskId, e);
        }
    }

    /**
     * 根据业务键获取任务记录
     *
     * @param taskGroup   任务组名
     * @param businessKey 业务键
     * @return 任务记录
     */
    @Override
    public Optional<LeaseTaskRecord> getByBusinessKey(String taskGroup, String businessKey) {
        if (StringUtil.isBlank(taskGroup) || StringUtil.isBlank(businessKey)) {
            return Optional.empty();
        }
        try {
            LeaseTaskEntity entity = dao.findByBusinessKey(taskGroup, businessKey);
            return entity == null ? Optional.empty() : Optional.of(entity.toRecord());
        } catch (SQLException e) {
            throw new IllegalStateException("getByBusinessKey failed", e);
        }
    }

    /**
     * 管理面：更新任务的基础信息
     *
     * @param request 更新请求
     * @return 管理态操作结果
     */
    @Override
    public LeaseAdminResult update(LeaseUpdateRequest request) {
        return applyAdminMutation(request.getTaskId(), now -> dao.update(request, now));
    }

    /**
     * 管理面：更新任务基础信息并重新进行调度
     *
     * @param request     更新请求
     * @param delayMillis 调度延迟毫秒数
     * @return 管理态操作结果
     */
    @Override
    public LeaseAdminResult updateAndReschedule(LeaseUpdateRequest request, long delayMillis) {
        validateUpdateRequest(request);
        return applyAdminMutation(
                request.getTaskId(),
                now -> dao.updateAndReschedule(request, now + Math.max(0L, delayMillis), now));
    }

    /**
     * 分页列出符合条件的任务
     *
     * @param request 查询条件请求
     * @return 任务分页结果
     */
    @Override
    public LeaseTaskPage list(LeaseQueryRequest request) {
        try {
            return dao.query(request);
        } catch (SQLException e) {
            throw new IllegalStateException("list failed", e);
        }
    }

    /**
     * 执行单次抢占尝试
     * <p>
     * 1. 根据订阅信息扫描候选任务。
     * 2. 利用数据库的主键+版本/状态乐观锁 {@link JdbcLeaseTaskDao#tryAcquire} 进行原子性写回。
     * 3. 通过 version 乐观锁避免返回过期快照，并在成功后直接组装授权结果。
     */
    private LeaseGrant tryAcquireOnce(LeaseAcquireRequest request) {
        long now = now();
        try {
            for (LeaseTaskEntity candidate : dao.findAcquirableTasks(request.getSubscriptions(), now,
                    ACQUIRE_BATCH_SIZE)) {
                String leaseToken = nextLeaseToken();
                long leaseExpiresAt = now + Math.max(1L, request.getLeaseMillis());
                int updated = dao.tryAcquire(
                        candidate.getTaskId(),
                        request.getWorkerId(),
                        leaseToken,
                        leaseExpiresAt,
                        now,
                        candidate.getVersion());
                if (updated == 1) {
                    return candidate.toBuilder()
                            .state(LeaseTaskState.RUNNING)
                            .workerId(request.getWorkerId())
                            .leaseToken(leaseToken)
                            .leaseExpiresAtMillis(leaseExpiresAt)
                            .deliveryCount(candidate.getDeliveryCount() + 1)
                            .updatedAtMillis(now)
                            .version(candidate.getVersion() + 1)
                            .build()
                            .toGrant();
                }
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("tryAcquireOnce failed", e);
        }
    }

    private LeaseRuntimeResult applyRuntimeMutation(LeaseHandle handle, RuntimeMutation mutation) {
        validateHandle(handle);
        long now = now();
        try {
            int updated = mutation.apply(now);
            if (updated == 1) {
                return LeaseRuntimeResult.APPLIED;
            }
            LeaseTaskEntity current = dao.findById(handle.getTaskId());
            return classifyRuntimeMutation(current);
        } catch (SQLException e) {
            throw new IllegalStateException("runtime mutation failed: " + handle.getTaskId(), e);
        }
    }

    private LeaseRuntimeResult classifyRuntimeMutation(LeaseTaskEntity current) {
        if (current == null) {
            return LeaseRuntimeResult.TASK_NOT_FOUND;
        }
        if (isTerminal(current)) {
            return LeaseRuntimeResult.CLOSED;
        }
        return LeaseRuntimeResult.LEASE_LOST;
    }

    private LeaseAdminResult applyAdminMutation(String taskId, AdminMutation mutation) {
        validateTaskId(taskId);
        try {
            long now = now();
            int updated = mutation.apply(now);
            if (updated == 1) {
                return LeaseAdminResult.APPLIED;
            }
            LeaseTaskEntity latest = dao.findById(taskId);
            return classifyAdminMutation(latest, now);
        } catch (SQLException e) {
            throw new IllegalStateException("admin mutation failed: " + taskId, e);
        }
    }

    private void validateUpdateRequest(LeaseUpdateRequest request) {
        if (request == null || StringUtil.isBlank(request.getTaskId())) {
            throw new IllegalArgumentException("taskId required");
        }
    }

    private LeaseAdminResult classifyAdminMutation(LeaseTaskEntity current, long now) {
        if (current == null) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        if (isTerminal(current)) {
            return LeaseAdminResult.CLOSED;
        }
        if (hasActiveLease(current, now)) {
            return LeaseAdminResult.ACTIVE_LEASE_PRESENT;
        }
        return LeaseAdminResult.APPLIED;
    }

    private boolean hasActiveLease(LeaseTaskEntity task, long now) {
        return task.getState() == LeaseTaskState.RUNNING
                && task.getLeaseExpiresAtMillis() >= now;
    }

    private boolean isTerminal(LeaseTaskEntity task) {
        return task.getState() == LeaseTaskState.CLOSED;
    }

    private void validatePublishRequest(LeasePublishRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.notBlank(request.getTaskGroup(), "request.taskGroup must not be blank");
        Assert.notBlank(request.getTaskType(), "request.taskType must not be blank");
    }

    private void validateAcquireRequest(LeaseAcquireRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.notBlank(request.getWorkerId(), "request.workerId must not be blank");
        if (request.getLeaseMillis() <= 0L) {
            throw new IllegalArgumentException("request.leaseMillis must be greater than 0");
        }
        Assert.notEmpty(request.getSubscriptions(),
                "request.subscriptions must not be empty");
        for (LeaseTaskGroupSubscription subscription : request.getSubscriptions()) {
            if (subscription == null || StringUtil.isBlank(subscription.getTaskGroup())) {
                throw new IllegalArgumentException("subscription.taskGroup must not be blank");
            }
        }
    }

    private void validateHandle(LeaseHandle handle) {
        Assert.notNull(handle, "handle must not be null");
        validateTaskId(handle.getTaskId());
        Assert.notBlank(handle.getWorkerId(), "handle.workerId must not be blank");
        Assert.notBlank(handle.getLeaseToken(), "handle.leaseToken must not be blank");
    }

    private void validateTaskId(String taskId) {
        if (StringUtil.isBlank(taskId)) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
    }

    private String nextTaskId() {
        return "lease-task-" + IdUtil.simpleUUID();
    }

    private String nextLeaseToken() {
        return "lease-token-" + IdUtil.simpleUUID();
    }

    /**
     * 根据发布请求创建初始态的任务实体
     *
     * @param request 发布请求
     * @return 初始化的任务实体，其状态为 READY
     */
    private LeaseTaskEntity newPublishedEntity(LeasePublishRequest request) {
        long now = now();
        return LeaseTaskEntity.builder()
                .taskId(nextTaskId())
                .taskGroup(request.getTaskGroup())
                .taskType(request.getTaskType())
                .payload(request.getPayload())
                .businessKey(request.getBusinessKey())
                .state(LeaseTaskState.READY)
                .outcome(null)
                .failureReason(null)
                .priority(request.getPriority())
                .deliveryCount(0)
                .failureCount(0)
                .workerId(null)
                .leaseToken(null)
                .leaseExpiresAtMillis(0L)
                .visibleAtMillis(now + Math.max(0L, request.getDelayMillis()))
                .createdAtMillis(now)
                .updatedAtMillis(now)
                .version(0L)
                .errorMessage(null)
                .attributes(request.getAttributes())
                .build();
    }

    /**
     * 将任务实体持久化至数据库
     *
     * @param entity 待插入的任务实体
     */
    private void insert(LeaseTaskEntity entity) {
        try {
            dao.insert(entity);
        } catch (SQLException e) {
            throw new IllegalStateException("publish failed: " + entity.getTaskId(), e);
        }
    }

    /**
     * 判定 SQL 异常是否由唯一键冲突导致
     * <p>
     * 通过递归检查异常链，匹配标准 SQL 状态码（23 开头代表完整性约束异常）或特定异常类型。
     *
     * @param e 捕获到的 SQL 异常
     * @return 如果判定为重复键冲突则返回 true
     */
    private boolean isDuplicateKey(SQLException e) {
        SQLException current = e;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            String sqlState = current.getSQLState();
            if (sqlState != null && sqlState.startsWith("23")) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    /**
     * 获取系统当前时间戳
     *
     * @return 毫秒级时间戳
     */
    private long now() {
        return clock.getAsLong();
    }

    @FunctionalInterface
    private interface RuntimeMutation {
        int apply(long now) throws SQLException;
    }

    @FunctionalInterface
    private interface AdminMutation {
        int apply(long now) throws SQLException;
    }
}
