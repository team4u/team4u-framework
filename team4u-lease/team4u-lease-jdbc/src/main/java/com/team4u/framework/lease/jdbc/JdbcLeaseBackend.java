package com.team4u.framework.lease.jdbc;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Db;
import com.team4u.framework.lease.*;
import com.team4u.framework.lease.jdbc.codec.LeaseJsonCodec;
import com.team4u.framework.lease.jdbc.dialect.LeaseDbDialect;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

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

    public JdbcLeaseBackend(DataSource dataSource) {
        this(dataSource, new MySqlLeaseDbDialect());
    }

    public JdbcLeaseBackend(DataSource dataSource, LeaseDbDialect dialect) {
        this.dao = new JdbcLeaseTaskDao(Db.use(dataSource), dialect, new LeaseJsonCodec());
    }

    @Override
    public String publish(LeasePublishRequest request) {
        validatePublishRequest(request);
        long now = System.currentTimeMillis();
        String taskId = nextTaskId();
        LeaseTaskEntity entity = LeaseTaskEntity.builder()
                .taskId(taskId)
                .queue(request.getQueue())
                .taskType(request.getTaskType())
                .payload(request.getPayload())
                .status(LeaseTaskStatus.SCHEDULED)
                .priority(request.getPriority())
                .deliveryCount(0)
                .failureCount(0)
                .workerId(null)
                .leaseToken(null)
                .leaseExpiresAtMillis(0L)
                .visibleAtMillis(now + Math.max(0L, request.getDelayMillis()))
                .createdAtMillis(now)
                .updatedAtMillis(now)
                .lastError(null)
                .attributes(request.getAttributes())
                .build();
        try {
            dao.insert(entity);
            return taskId;
        } catch (SQLException e) {
            throw new IllegalStateException("publish failed: " + taskId, e);
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
        long deadline = System.currentTimeMillis() + timeout;
        while (true) {
            LeaseGrant grant = tryAcquireOnce(request);
            if (grant != null) {
                return grant;
            }
            if (timeout == 0L) {
                return null;
            }
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                return null;
            }
            Thread.sleep(Math.min(DEFAULT_WAIT_POLL_MILLIS, deadline - now));
        }
    }

    @Override
    public LeaseRuntimeResult ack(LeaseHandle handle) {
        return applyRuntimeMutation(handle, now -> dao.ack(handle.getTaskId(), handle.getWorkerId(), handle.getLeaseToken(), now));
    }

    @Override
    public LeaseRuntimeResult retry(LeaseHandle handle, long delayMillis, Throwable cause) {
        return applyRuntimeMutation(handle, now -> dao.retry(
                handle.getTaskId(),
                handle.getWorkerId(),
                handle.getLeaseToken(),
                now + Math.max(0L, delayMillis),
                errorMessage(cause),
                now
        ));
    }

    @Override
    public LeaseRuntimeResult fail(LeaseHandle handle, Throwable cause) {
        return applyRuntimeMutation(handle, now -> dao.fail(
                handle.getTaskId(),
                handle.getWorkerId(),
                handle.getLeaseToken(),
                errorMessage(cause),
                now
        ));
    }

    @Override
    public LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
        return applyRuntimeMutation(handle, now -> dao.heartbeat(
                handle.getTaskId(),
                handle.getWorkerId(),
                handle.getLeaseToken(),
                now + Math.max(1L, extendMillis),
                now
        ));
    }

    @Override
    public LeaseRuntimeResult release(LeaseHandle handle, long delayMillis) {
        return applyRuntimeMutation(handle, now -> dao.release(
                handle.getTaskId(),
                handle.getWorkerId(),
                handle.getLeaseToken(),
                now + Math.max(0L, delayMillis),
                now
        ));
    }

    @Override
    public LeaseAdminResult reschedule(String taskId, long delayMillis) {
        return applyAdminMutation(taskId, now -> dao.reschedule(taskId, now + Math.max(0L, delayMillis), now));
    }

    @Override
    public LeaseAdminResult cancel(String taskId) {
        return applyAdminMutation(taskId, now -> dao.cancel(taskId, "cancelled", now));
    }

    @Override
    public LeaseAdminResult requeueDead(String taskId, long delayMillis) {
        validateTaskId(taskId);
        try {
            LeaseTaskEntity current = dao.findById(taskId);
            if (current == null) {
                return LeaseAdminResult.TASK_NOT_FOUND;
            }
            if (current.getStatus() != LeaseTaskStatus.DEAD) {
                return LeaseAdminResult.TERMINAL;
            }
            long now = System.currentTimeMillis();
            return dao.requeueDead(taskId, now + Math.max(0L, delayMillis), now) == 1
                    ? LeaseAdminResult.APPLIED
                    : LeaseAdminResult.TERMINAL;
        } catch (SQLException e) {
            throw new IllegalStateException("requeueDead failed: " + taskId, e);
        }
    }

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
     * 3. 校验更新结果，确保当前 Worker 确实持有了该任务。
     */
    private LeaseGrant tryAcquireOnce(LeaseAcquireRequest request) {
        long now = System.currentTimeMillis();
        try {
            for (LeaseTaskEntity candidate : dao.findAcquirableTasks(request.getSubscriptions(), now, ACQUIRE_BATCH_SIZE)) {
                String leaseToken = nextLeaseToken();
                long leaseExpiresAt = now + Math.max(1L, request.getLeaseMillis());
                int updated = dao.tryAcquire(
                        candidate.getTaskId(),
                        request.getWorkerId(),
                        leaseToken,
                        leaseExpiresAt,
                        now
                );
                if (updated == 1) {
                    LeaseTaskEntity claimed = dao.findById(candidate.getTaskId());
                    if (claimed != null
                            && claimed.getStatus() == LeaseTaskStatus.LEASED
                            && Objects.equals(claimed.getWorkerId(), request.getWorkerId())
                            && Objects.equals(claimed.getLeaseToken(), leaseToken)) {
                        return claimed.toGrant();
                    }
                }
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("tryAcquireOnce failed", e);
        }
    }

    private LeaseRuntimeResult applyRuntimeMutation(LeaseHandle handle, RuntimeMutation mutation) {
        validateHandle(handle);
        long now = System.currentTimeMillis();
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
            return LeaseRuntimeResult.TERMINAL;
        }
        return LeaseRuntimeResult.LEASE_LOST;
    }

    private LeaseAdminResult applyAdminMutation(String taskId, AdminMutation mutation) {
        validateTaskId(taskId);
        try {
            long now = System.currentTimeMillis();
            int updated = mutation.apply(now);
            if (updated == 1) {
                return LeaseAdminResult.APPLIED;
            }
            LeaseTaskEntity latest = dao.findById(taskId);
            return classifyAdminMutation(latest);
        } catch (SQLException e) {
            throw new IllegalStateException("admin mutation failed: " + taskId, e);
        }
    }

    private LeaseAdminResult classifyAdminMutation(LeaseTaskEntity current) {
        if (current == null) {
            return LeaseAdminResult.TASK_NOT_FOUND;
        }
        if (isTerminal(current)) {
            return LeaseAdminResult.TERMINAL;
        }
        if (hasActiveLease(current)) {
            return LeaseAdminResult.ACTIVE_LEASE_PRESENT;
        }
        return LeaseAdminResult.APPLIED;
    }

    private boolean hasActiveLease(LeaseTaskEntity task) {
        return task.getStatus() == LeaseTaskStatus.LEASED
                && task.getLeaseExpiresAtMillis() >= System.currentTimeMillis();
    }

    private boolean isTerminal(LeaseTaskEntity task) {
        return task.getStatus() == LeaseTaskStatus.SUCCEEDED || task.getStatus() == LeaseTaskStatus.DEAD;
    }

    private void validatePublishRequest(LeasePublishRequest request) {
        ObjectUtil.defaultIfNull(request, () -> {
            throw new IllegalArgumentException("request must not be null");
        });
        if (StrUtil.isBlank(request.getQueue())) {
            throw new IllegalArgumentException("request.queue must not be blank");
        }
        if (StrUtil.isBlank(request.getTaskType())) {
            throw new IllegalArgumentException("request.taskType must not be blank");
        }
    }

    private void validateAcquireRequest(LeaseAcquireRequest request) {
        ObjectUtil.defaultIfNull(request, () -> {
            throw new IllegalArgumentException("request must not be null");
        });
        if (StrUtil.isBlank(request.getWorkerId())) {
            throw new IllegalArgumentException("request.workerId must not be blank");
        }
        if (request.getLeaseMillis() <= 0L) {
            throw new IllegalArgumentException("request.leaseMillis must be greater than 0");
        }
        if (request.getSubscriptions().isEmpty()) {
            throw new IllegalArgumentException("request.subscriptions must not be empty");
        }
        for (LeaseSubscription subscription : request.getSubscriptions()) {
            if (subscription == null || StrUtil.isBlank(subscription.getQueue())) {
                throw new IllegalArgumentException("subscription.queue must not be blank");
            }
        }
    }

    private void validateHandle(LeaseHandle handle) {
        ObjectUtil.defaultIfNull(handle, () -> {
            throw new IllegalArgumentException("handle must not be null");
        });
        validateTaskId(handle.getTaskId());
        if (StrUtil.isBlank(handle.getWorkerId())) {
            throw new IllegalArgumentException("handle.workerId must not be blank");
        }
        if (StrUtil.isBlank(handle.getLeaseToken())) {
            throw new IllegalArgumentException("handle.leaseToken must not be blank");
        }
    }

    private void validateTaskId(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
    }

    private String nextTaskId() {
        return "lease-task-" + IdUtil.fastSimpleUUID();
    }

    private String nextLeaseToken() {
        return "lease-token-" + IdUtil.fastSimpleUUID();
    }

    private String errorMessage(Throwable cause) {
        return cause == null ? null : cause.toString();
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
