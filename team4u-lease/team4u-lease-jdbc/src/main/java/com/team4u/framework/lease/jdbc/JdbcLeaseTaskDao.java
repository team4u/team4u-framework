package com.team4u.framework.lease.jdbc;

import cn.hutool.core.convert.Convert;
import cn.hutool.db.Db;
import cn.hutool.db.Entity;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.jdbc.codec.LeaseJsonCodec;
import com.team4u.framework.lease.jdbc.dialect.LeaseDbDialect;
import com.team4u.framework.lease.model.*;

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
    public static final String COLUMNS = "task_id, queue_name, task_type, payload, state, outcome, failure_reason, "
            + "priority, delivery_count, failure_count, worker_id, lease_token, lease_expires_at, visible_at, "
            + "created_at, updated_at, error_message, attributes_json";

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
                .set("state", entity.getState().name())
                .set("outcome", entity.getOutcome() == null ? null : entity.getOutcome().name())
                .set("failure_reason", entity.getFailureReason() == null ? null : entity.getFailureReason().name())
                .set("priority", entity.getPriority())
                .set("delivery_count", entity.getDeliveryCount())
                .set("failure_count", entity.getFailureCount())
                .set("worker_id", entity.getWorkerId())
                .set("lease_token", entity.getLeaseToken())
                .set("lease_expires_at", entity.getLeaseExpiresAtMillis())
                .set("visible_at", entity.getVisibleAtMillis())
                .set("created_at", entity.getCreatedAtMillis())
                .set("updated_at", entity.getUpdatedAtMillis())
                .set("error_message", entity.getErrorMessage())
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
                taskId);
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
     * 1. 任务处于 READY 状态且已过可见时间（visible_at）。
     * 2. 任务处于 RUNNING 状态但租约已过期（lease_expires_at），即原持有节点疑似宕机或执行超时。
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
                "UPDATE " + TABLE_NAME + " SET state = ?, worker_id = ?, lease_token = ?, lease_expires_at = ?, "
                        + "delivery_count = delivery_count + 1, updated_at = ? "
                        + "WHERE task_id = ? "
                        + "AND ((state = ? AND visible_at <= ?) OR (state = ? AND lease_expires_at <= ?))",
                LeaseTaskState.RUNNING.name(),
                workerId,
                leaseToken,
                leaseExpiresAt,
                now,
                taskId,
                LeaseTaskState.READY.name(),
                now,
                LeaseTaskState.RUNNING.name(),
                now);
    }

    /**
     * 关闭运行中的任务
     *
     * @param taskId     任务 ID
     * @param workerId   工作节点 ID
     * @param leaseToken 租约令牌
     * @param now        当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int close(String taskId, String workerId, String leaseToken, LeaseCloseRequest request, long now)
            throws SQLException {
        LeaseCloseRequest safeRequest = request == null
                ? LeaseCloseRequest.succeeded()
                : request.normalizeForRuntime();
        LeaseTaskOutcome outcome = safeRequest.getOutcome();
        LeaseTaskFailureReason reason = outcome == LeaseTaskOutcome.FAILED ? safeRequest.getFailureReason() : null;
        int failureIncrement = outcome == LeaseTaskOutcome.FAILED ? 1 : 0;
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET state = ?, outcome = ?, failure_reason = ?, "
                        + "failure_count = failure_count + ?, worker_id = NULL, lease_token = NULL, lease_expires_at = 0, "
                        + "error_message = ?, payload = COALESCE(?, payload), attributes_json = COALESCE(?, attributes_json), "
                        + "updated_at = ? "
                        + "WHERE task_id = ? AND state = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                LeaseTaskState.CLOSED.name(),
                outcome.name(),
                reason == null ? null : reason.name(),
                failureIncrement,
                safeRequest.getErrorMessage(),
                safeRequest.getPayload(),
                safeRequest.getAttributes().isEmpty() ? null : jsonCodec.toJson(safeRequest.getAttributes()),
                now,
                taskId,
                LeaseTaskState.RUNNING.name(),
                workerId,
                leaseToken,
                now);
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
                        + "WHERE task_id = ? AND state = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                leaseExpiresAt,
                now,
                taskId,
                LeaseTaskState.RUNNING.name(),
                workerId,
                leaseToken,
                now);
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
    public int release(
            String taskId,
            String workerId,
            String leaseToken,
            long visibleAt,
            String payload,
            String errorMessage,
            long now)
            throws SQLException {
        Entity entity = Entity.create(TABLE_NAME);
        entity.set("state", LeaseTaskState.READY.name());
        entity.set("outcome", null);
        entity.set("failure_reason", null);
        entity.set("visible_at", visibleAt);
        entity.set("worker_id", null);
        entity.set("lease_token", null);
        entity.set("lease_expires_at", 0);
        entity.set("error_message", errorMessage);
        if (payload != null) {
            entity.set("payload", payload);
        }
        entity.set("updated_at", now);
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET "
                        + buildUpdateAssignments(entity)
                        + " WHERE task_id = ? AND state = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                buildRuntimeReleaseParams(entity, taskId, workerId, leaseToken, now));
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
                "UPDATE " + TABLE_NAME
                        + " SET state = ?, outcome = NULL, failure_reason = NULL, error_message = NULL, visible_at = ?, "
                        + "worker_id = NULL, lease_token = NULL, lease_expires_at = 0, updated_at = ? WHERE task_id = ? "
                        + "AND state <> ? "
                        + "AND NOT (state = ? AND lease_expires_at >= ?)",
                LeaseTaskState.READY.name(),
                visibleAt,
                now,
                taskId,
                LeaseTaskState.CLOSED.name(),
                LeaseTaskState.RUNNING.name(),
                now);
    }

    /**
     * 管理面关闭任务
     */
    public int close(String taskId, LeaseCloseRequest request, long now) throws SQLException {
        LeaseCloseRequest safeRequest = request == null
                ? LeaseCloseRequest.cancelled(null)
                : request.normalizeForAdmin();
        LeaseTaskOutcome outcome = safeRequest.getOutcome();
        LeaseTaskFailureReason reason = outcome == LeaseTaskOutcome.FAILED ? safeRequest.getFailureReason() : null;
        int failureIncrement = outcome == LeaseTaskOutcome.FAILED ? 1 : 0;
        return db.execute(
                "UPDATE " + TABLE_NAME
                        + " SET state = ?, outcome = ?, failure_reason = ?, failure_count = failure_count + ?, "
                        + "worker_id = NULL, lease_token = NULL, lease_expires_at = 0, error_message = ?, "
                        + "payload = COALESCE(?, payload), attributes_json = COALESCE(?, attributes_json), "
                        + "updated_at = ? WHERE task_id = ? AND state <> ? AND NOT (state = ? AND lease_expires_at >= ?)",
                LeaseTaskState.CLOSED.name(),
                outcome.name(),
                reason == null ? null : reason.name(),
                failureIncrement,
                safeRequest.getErrorMessage(),
                safeRequest.getPayload(),
                safeRequest.getAttributes().isEmpty() ? null : jsonCodec.toJson(safeRequest.getAttributes()),
                now,
                taskId,
                LeaseTaskState.CLOSED.name(),
                LeaseTaskState.RUNNING.name(),
                now);
    }

    /**
     * 将失败任务重新放入队列
     */
    public int requeueFailed(String taskId, long visibleAt, long now) throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME
                        + " SET state = ?, outcome = NULL, failure_reason = NULL, error_message = NULL, visible_at = ?, "
                        + "worker_id = NULL, lease_token = NULL, lease_expires_at = 0, updated_at = ? "
                        + "WHERE task_id = ? AND state = ? AND outcome = ?",
                LeaseTaskState.READY.name(),
                visibleAt,
                now,
                taskId,
                LeaseTaskState.CLOSED.name(),
                LeaseTaskOutcome.FAILED.name());
    }

    /**
     * 更新任务属性（运维接口）
     *
     * @param request 更新请求
     * @param now     当前时间
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int update(com.team4u.framework.lease.model.LeaseUpdateRequest request, long now) throws SQLException {
        Entity entity = Entity.create(TABLE_NAME);
        if (request.getTaskType() != null) {
            entity.set("task_type", request.getTaskType());
        }
        if (request.getPayload() != null) {
            entity.set("payload", request.getPayload());
        }
        if (request.getPriority() != null) {
            entity.set("priority", request.getPriority());
        }
        if (request.getAttributes() != null) {
            entity.set("attributes_json", jsonCodec.toJson(request.getAttributes()));
        }
        if (entity.isEmpty()) {
            return 0;
        }
        entity.set("updated_at", now);
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET "
                        + buildUpdateAssignments(entity)
                        + " WHERE task_id = ? AND state <> ? AND NOT (state = ? AND lease_expires_at >= ?)",
                buildUpdateParams(entity, request.getTaskId(), now));
    }

    private String buildUpdateAssignments(Entity entity) {
        StringBuilder sql = new StringBuilder();
        for (String field : entity.keySet()) {
            if (sql.length() > 0) {
                sql.append(", ");
            }
            sql.append(field).append(" = ?");
        }
        return sql.toString();
    }

    private Object[] buildUpdateParams(Entity entity, String taskId, long now) {
        List<Object> params = new ArrayList<Object>(entity.size() + 4);
        for (String field : entity.keySet()) {
            params.add(entity.get(field));
        }
        params.add(taskId);
        params.add(LeaseTaskState.CLOSED.name());
        params.add(LeaseTaskState.RUNNING.name());
        params.add(now);
        return params.toArray();
    }

    private Object[] buildRuntimeReleaseParams(
            Entity entity,
            String taskId,
            String workerId,
            String leaseToken,
            long now) {
        List<Object> params = new ArrayList<>(entity.size() + 5);
        for (String field : entity.keySet()) {
            params.add(entity.get(field));
        }
        params.add(taskId);
        params.add(LeaseTaskState.RUNNING.name());
        params.add(workerId);
        params.add(leaseToken);
        params.add(now);
        return params.toArray();
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
        boolean filterStates = !safeRequest.getStates().isEmpty();
        boolean filterOutcomes = !safeRequest.getOutcomes().isEmpty();
        boolean filterFailureReasons = !safeRequest.getFailureReasons().isEmpty();
        boolean filterWorkerId = safeRequest.getWorkerId() != null;

        String sql = dialect.buildQuerySql(
                filterQueue,
                filterTaskType,
                filterStates ? safeRequest.getStates().size() : 0,
                filterOutcomes ? safeRequest.getOutcomes().size() : 0,
                filterFailureReasons ? safeRequest.getFailureReasons().size() : 0,
                filterWorkerId);

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
        if (!safeRequest.getStates().isEmpty()) {
            sql.append(" AND state IN (").append(placeholders(safeRequest.getStates().size())).append(")");
        }
        if (!safeRequest.getOutcomes().isEmpty()) {
            sql.append(" AND outcome IN (").append(placeholders(safeRequest.getOutcomes().size())).append(")");
        }
        if (!safeRequest.getFailureReasons().isEmpty()) {
            sql.append(" AND failure_reason IN (").append(placeholders(safeRequest.getFailureReasons().size()))
                    .append(")");
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
        if (!request.getStates().isEmpty()) {
            for (LeaseTaskState state : request.getStates()) {
                params.add(state.name());
            }
        }
        if (!request.getOutcomes().isEmpty()) {
            for (LeaseTaskOutcome outcome : request.getOutcomes()) {
                params.add(outcome.name());
            }
        }
        if (!request.getFailureReasons().isEmpty()) {
            for (LeaseTaskFailureReason failureReason : request.getFailureReasons()) {
                params.add(failureReason.name());
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
                .state(LeaseTaskState.valueOf(row.getStr("state")))
                .outcome(row.getStr("outcome") == null ? null : LeaseTaskOutcome.valueOf(row.getStr("outcome")))
                .failureReason(row.getStr("failure_reason") == null ? null
                        : LeaseTaskFailureReason.valueOf(row.getStr("failure_reason")))
                .priority(Convert.toInt(row.get("priority"), 0))
                .deliveryCount(Convert.toInt(row.get("delivery_count"), 0))
                .failureCount(Convert.toInt(row.get("failure_count"), 0))
                .workerId(row.getStr("worker_id"))
                .leaseToken(row.getStr("lease_token"))
                .leaseExpiresAtMillis(Convert.toLong(row.get("lease_expires_at"), 0L))
                .visibleAtMillis(Convert.toLong(row.get("visible_at"), 0L))
                .createdAtMillis(Convert.toLong(row.get("created_at"), 0L))
                .updatedAtMillis(Convert.toLong(row.get("updated_at"), 0L))
                .errorMessage(row.getStr("error_message"))
                .attributes(jsonCodec.fromJson(row.getStr("attributes_json")))
                .build();
    }
}
