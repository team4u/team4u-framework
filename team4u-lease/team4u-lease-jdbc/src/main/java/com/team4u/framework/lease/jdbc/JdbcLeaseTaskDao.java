package com.team4u.framework.lease.jdbc;

import cn.hutool.core.convert.Convert;
import cn.hutool.db.Db;
import cn.hutool.db.Entity;
import com.team4u.framework.lease.enums.LeaseTaskStatus;
import com.team4u.framework.lease.jdbc.codec.LeaseJsonCodec;
import com.team4u.framework.lease.jdbc.dialect.LeaseDbDialect;
import com.team4u.framework.lease.model.LeaseQueryRequest;
import com.team4u.framework.lease.model.LeaseSubscription;
import com.team4u.framework.lease.model.LeaseTaskPage;
import com.team4u.framework.lease.model.LeaseTaskRecord;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 基于 JDBC 的租赁任务数据访问对象
 * <p>
 * 封装了对 `lease_task` 表的所有底层 SQL 操作，包括任务检索、状态乐观锁更新及复杂的分页统计查询。
 * 该 DAO 依赖各数据库方言 {@link LeaseDbDialect} 来处理分页和锁语法差异。
 *
 * @author jay.wu
 */
public class JdbcLeaseTaskDao {

    /**
     * 表名
     */
    public static final String TABLE_NAME = "lease_task";

    /**
     * 字段列表
     */
    public static final String COLUMNS = "task_id, queue_name, task_type, payload, status, priority, " +
            "delivery_count, failure_count, worker_id, lease_token, lease_expires_at, visible_at, " +
            "created_at, updated_at, last_error, attributes_json";

    private final Db db;
    private final LeaseDbDialect dialect;
    private final LeaseJsonCodec jsonCodec;

    public JdbcLeaseTaskDao(Db db, LeaseDbDialect dialect, LeaseJsonCodec jsonCodec) {
        this.db = db;
        this.dialect = dialect;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 插入新任务
     *
     * @param entity 任务实体
     * @throws SQLException SQL 异常
     */
    public void insert(LeaseTaskEntity entity) throws SQLException {
        db.insert(Entity.create(TABLE_NAME)
                .set("task_id", entity.getTaskId())
                .set("queue_name", entity.getQueue())
                .set("task_type", entity.getTaskType())
                .set("payload", entity.getPayload())
                .set("status", entity.getStatus().name())
                .set("priority", entity.getPriority())
                .set("delivery_count", entity.getDeliveryCount())
                .set("failure_count", entity.getFailureCount())
                .set("worker_id", entity.getWorkerId())
                .set("lease_token", entity.getLeaseToken())
                .set("lease_expires_at", entity.getLeaseExpiresAtMillis())
                .set("visible_at", entity.getVisibleAtMillis())
                .set("created_at", entity.getCreatedAtMillis())
                .set("updated_at", entity.getUpdatedAtMillis())
                .set("last_error", entity.getLastError())
                .set("attributes_json", jsonCodec.toJson(entity.getAttributes())));
    }

    /**
     * 根据任务 ID 查询任务
     *
     * @param taskId 任务 ID
     * @return 任务实体，不存在返回 null
     * @throws SQLException SQL 异常
     */
    public LeaseTaskEntity findById(String taskId) throws SQLException {
        List<Entity> rows = db.query(
                "SELECT " + COLUMNS + " FROM " + TABLE_NAME + " WHERE task_id = ?",
                taskId
        );
        return rows.isEmpty() ? null : toEntity(rows.get(0));
    }

    /**
     * 查找待处理的任务候选者
     *
     * @param subscriptions 订阅列表
     * @param now           当前时间戳
     * @param limit         限制数量
     * @return 任务候选者列表
     * @throws SQLException SQL 异常
     */
    public List<LeaseTaskEntity> findAcquirableTasks(Set<LeaseSubscription> subscriptions, long now, int limit)
            throws SQLException {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> params = new ArrayList<Object>();
        for (LeaseSubscription subscription : subscriptions) {
            params.add(subscription.getQueue());
        }
        params.add(now);
        params.add(now);
        params.add(limit);
        return toEntities(db.query(dialect.buildAcquireCandidateSql(subscriptions.size()), params.toArray()));
    }

    /**
     * 尝试原子性抢占租约（核心乐观锁实现）
     * <p>
     * 该 SQL 确保只有满足以下条件之一的任务才能被抢占：
     * 1. 任务处于 SCHEDULED 状态且已过可见时间（visible_at）。
     * 2. 任务处于 LEASED 状态但租约已过期（lease_expires_at），即原持有节点疑似宕机或执行超时。
     *
     * @param taskId         任务 ID
     * @param workerId       抢占该租约的工作节点 ID
     * @param leaseToken     本次授权的唯一令牌
     * @param leaseExpiresAt 设定的租约过期时间戳
     * @param now            当前时间戳，用于可见性与过期判定
     * @return 更新行数，1 表示抢占成功，0 表示已被其他节点抢占
     * @throws SQLException SQL 异常
     */
    public int tryAcquire(String taskId, String workerId, String leaseToken, long leaseExpiresAt, long now)
            throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET status = ?, worker_id = ?, lease_token = ?, lease_expires_at = ?, "
                        + "delivery_count = delivery_count + 1, updated_at = ? "
                        + "WHERE task_id = ? "
                        + "AND ((status = ? AND visible_at <= ?) OR (status = ? AND lease_expires_at <= ?))",
                LeaseTaskStatus.LEASED.name(),
                workerId,
                leaseToken,
                leaseExpiresAt,
                now,
                taskId,
                LeaseTaskStatus.SCHEDULED.name(),
                now,
                LeaseTaskStatus.LEASED.name(),
                now
        );
    }

    /**
     * 确认任务完成
     *
     * @param taskId     任务 ID
     * @param workerId   工作节点 ID
     * @param leaseToken 租约令牌
     * @param now        当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int ack(String taskId, String workerId, String leaseToken, long now) throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET status = ?, worker_id = NULL, lease_token = NULL, lease_expires_at = 0, "
                        + "last_error = NULL, updated_at = ? "
                        + "WHERE task_id = ? AND status = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                LeaseTaskStatus.SUCCEEDED.name(),
                now,
                taskId,
                LeaseTaskStatus.LEASED.name(),
                workerId,
                leaseToken,
                now
        );
    }

    /**
     * 重试任务
     *
     * @param taskId     任务 ID
     * @param workerId   工作节点 ID
     * @param leaseToken 租约令牌
     * @param visibleAt  下次可见时间戳
     * @param lastError  错误原因
     * @param now        当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int retry(String taskId, String workerId, String leaseToken, long visibleAt, String lastError, long now)
            throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET status = ?, visible_at = ?, failure_count = failure_count + 1, worker_id = NULL, "
                        + "lease_token = NULL, lease_expires_at = 0, last_error = ?, updated_at = ? "
                        + "WHERE task_id = ? AND status = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                LeaseTaskStatus.SCHEDULED.name(),
                visibleAt,
                lastError,
                now,
                taskId,
                LeaseTaskStatus.LEASED.name(),
                workerId,
                leaseToken,
                now
        );
    }

    /**
     * 任务标记为失败（不再重试）
     *
     * @param taskId     任务 ID
     * @param workerId   工作节点 ID
     * @param leaseToken 租约令牌
     * @param lastError  错误原因
     * @param now        当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int fail(String taskId, String workerId, String leaseToken, String lastError, long now) throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET status = ?, failure_count = failure_count + 1, worker_id = NULL, lease_token = NULL, "
                        + "lease_expires_at = 0, last_error = ?, updated_at = ? "
                        + "WHERE task_id = ? AND status = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                LeaseTaskStatus.DEAD.name(),
                lastError,
                now,
                taskId,
                LeaseTaskStatus.LEASED.name(),
                workerId,
                leaseToken,
                now
        );
    }

    /**
     * 续延租约（心跳）
     *
     * @param taskId         任务 ID
     * @param workerId       工作节点 ID
     * @param leaseToken     租约令牌
     * @param leaseExpiresAt 新的过期时间戳
     * @param now            当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int heartbeat(String taskId, String workerId, String leaseToken, long leaseExpiresAt, long now)
            throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET lease_expires_at = ?, updated_at = ? "
                        + "WHERE task_id = ? AND status = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                leaseExpiresAt,
                now,
                taskId,
                LeaseTaskStatus.LEASED.name(),
                workerId,
                leaseToken,
                now
        );
    }

    /**
     * 释放当前租约（重入队列）
     *
     * @param taskId     任务 ID
     * @param workerId   工作节点 ID
     * @param leaseToken 租约令牌
     * @param visibleAt  下次可见时间
     * @param now        当前时间
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int release(String taskId, String workerId, String leaseToken, long visibleAt, long now) throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET status = ?, visible_at = ?, worker_id = NULL, lease_token = NULL, lease_expires_at = 0, "
                        + "updated_at = ? "
                        + "WHERE task_id = ? AND status = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                LeaseTaskStatus.SCHEDULED.name(),
                visibleAt,
                now,
                taskId,
                LeaseTaskStatus.LEASED.name(),
                workerId,
                leaseToken,
                now
        );
    }

    /**
     * 重新调度任务
     *
     * @param taskId    任务 ID
     * @param visibleAt 下次可见时间戳
     * @param now       当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int reschedule(String taskId, long visibleAt, long now) throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET status = ?, visible_at = ?, worker_id = NULL, lease_token = NULL, lease_expires_at = 0, "
                        + "updated_at = ? WHERE task_id = ? "
                        + "AND status NOT IN (?, ?) "
                        + "AND NOT (status = ? AND lease_expires_at >= ?)",
                LeaseTaskStatus.SCHEDULED.name(),
                visibleAt,
                now,
                taskId,
                LeaseTaskStatus.SUCCEEDED.name(),
                LeaseTaskStatus.DEAD.name(),
                LeaseTaskStatus.LEASED.name(),
                now
        );
    }

    /**
     * 取消任务
     *
     * @param taskId    任务 ID
     * @param lastError 错误消息
     * @param now       当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int cancel(String taskId, String lastError, long now) throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET status = ?, worker_id = NULL, lease_token = NULL, lease_expires_at = 0, "
                        + "last_error = ?, updated_at = ? WHERE task_id = ? "
                        + "AND status NOT IN (?, ?) "
                        + "AND NOT (status = ? AND lease_expires_at >= ?)",
                LeaseTaskStatus.DEAD.name(),
                lastError,
                now,
                taskId,
                LeaseTaskStatus.SUCCEEDED.name(),
                LeaseTaskStatus.DEAD.name(),
                LeaseTaskStatus.LEASED.name(),
                now
        );
    }

    /**
     * 将死信任务重新放入队列
     *
     * @param taskId    任务 ID
     * @param visibleAt 重入后的可见时间
     * @param now       当前时间
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int requeueDead(String taskId, long visibleAt, long now) throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET status = ?, visible_at = ?, worker_id = NULL, lease_token = NULL, lease_expires_at = 0, "
                        + "updated_at = ? WHERE task_id = ? AND status = ?",
                LeaseTaskStatus.SCHEDULED.name(),
                visibleAt,
                now,
                taskId,
                LeaseTaskStatus.DEAD.name()
        );
    }

    /**
     * 分页查询任务
     *
     * @param request 查询请求
     * @return 分页结果
     * @throws SQLException SQL 异常
     */
    public LeaseTaskPage query(LeaseQueryRequest request) throws SQLException {
        LeaseQueryRequest safeRequest = request == null ? LeaseQueryRequest.builder().build() : request;
        List<Object> params = new ArrayList<Object>();
        boolean filterQueue = safeRequest.getQueue() != null;
        boolean filterTaskType = safeRequest.getTaskType() != null;
        boolean filterStatuses = !safeRequest.getStatuses().isEmpty();
        boolean filterWorkerId = safeRequest.getWorkerId() != null;

        String sql = dialect.buildQuerySql(
                filterQueue,
                filterTaskType,
                filterStatuses ? safeRequest.getStatuses().size() : 0,
                filterWorkerId
        );

        applyQueryParams(safeRequest, params);

        int page = Math.max(0, safeRequest.getPage());
        int pageSize = safeRequest.getPageSize() <= 0 ? 50 : safeRequest.getPageSize();
        params.add(pageSize);
        params.add(page * pageSize);

        List<LeaseTaskEntity> items = toEntities(db.query(sql, params.toArray()));
        long total = count(safeRequest);

        List<LeaseTaskRecord> records = new ArrayList<LeaseTaskRecord>(items.size());
        for (LeaseTaskEntity item : items) {
            records.add(item.toRecord());
        }

        return LeaseTaskPage.builder()
                .total(total)
                .page(page)
                .pageSize(pageSize)
                .items(records)
                .build();
    }

    /**
     * 统计任务数量
     *
     * @param request 查询请求
     * @return 符合条件的任务总数
     * @throws SQLException SQL 异常
     */
    public long count(LeaseQueryRequest request) throws SQLException {
        LeaseQueryRequest safeRequest = request == null ? LeaseQueryRequest.builder().build() : request;
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total FROM ").append(TABLE_NAME).append(" WHERE 1=1");
        List<Object> params = new ArrayList<Object>();

        if (safeRequest.getQueue() != null) {
            sql.append(" AND queue_name = ?");
        }
        if (safeRequest.getTaskType() != null) {
            sql.append(" AND task_type = ?");
        }
        if (!safeRequest.getStatuses().isEmpty()) {
            sql.append(" AND status IN (").append(placeholders(safeRequest.getStatuses().size())).append(")");
        }
        if (safeRequest.getWorkerId() != null) {
            sql.append(" AND worker_id = ?");
        }

        applyQueryParams(safeRequest, params);

        List<Entity> rows = db.query(sql.toString(), params.toArray());
        if (rows.isEmpty()) {
            return 0L;
        }
        Number total = (Number) rows.get(0).get("total");
        return total == null ? 0L : total.longValue();
    }

    private void applyQueryParams(LeaseQueryRequest request, List<Object> params) {
        if (request.getQueue() != null) {
            params.add(request.getQueue());
        }
        if (request.getTaskType() != null) {
            params.add(request.getTaskType());
        }
        if (!request.getStatuses().isEmpty()) {
            for (LeaseTaskStatus status : request.getStatuses()) {
                params.add(status.name());
            }
        }
        if (request.getWorkerId() != null) {
            params.add(request.getWorkerId());
        }
    }

    private String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("?");
        }
        return builder.toString();
    }

    private List<LeaseTaskEntity> toEntities(List<Entity> rows) {
        List<LeaseTaskEntity> result = new ArrayList<LeaseTaskEntity>(rows.size());
        for (Entity row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private LeaseTaskEntity toEntity(Entity row) {
        return LeaseTaskEntity.builder()
                .taskId(row.getStr("task_id"))
                .queue(row.getStr("queue_name"))
                .taskType(row.getStr("task_type"))
                .payload(row.getStr("payload"))
                .status(LeaseTaskStatus.valueOf(row.getStr("status")))
                .priority(Convert.toInt(row.get("priority"), 0))
                .deliveryCount(Convert.toInt(row.get("delivery_count"), 0))
                .failureCount(Convert.toInt(row.get("failure_count"), 0))
                .workerId(row.getStr("worker_id"))
                .leaseToken(row.getStr("lease_token"))
                .leaseExpiresAtMillis(Convert.toLong(row.get("lease_expires_at"), 0L))
                .visibleAtMillis(Convert.toLong(row.get("visible_at"), 0L))
                .createdAtMillis(Convert.toLong(row.get("created_at"), 0L))
                .updatedAtMillis(Convert.toLong(row.get("updated_at"), 0L))
                .lastError(row.getStr("last_error"))
                .attributes(jsonCodec.fromJson(row.getStr("attributes_json")))
                .build();
    }
}
