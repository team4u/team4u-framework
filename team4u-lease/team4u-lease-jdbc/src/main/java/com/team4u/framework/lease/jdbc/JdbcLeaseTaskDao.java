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
    public static final String COLUMNS = "task_id, queue_name, task_type, payload, business_key, state, outcome, failure_reason, "
            + "priority, delivery_count, failure_count, worker_id, lease_token, lease_expires_at, visible_at, "
            + "created_at, updated_at, version, error_message, attributes_json";
    /**
     * 通用的管理面操作过滤条件占位符
     * 用于确保状态非 CLOSED，且如果处于 RUNNING 状态则必须已过期
     */
    private static final String WHERE_UNLOCKED_OR_EXPIRED = "task_id = ? AND state <> ? AND NOT (state = ? AND lease_expires_at >= ?)";
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
                .set("business_key", entity.getBusinessKey())
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
                .set("version", entity.getVersion())
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

    public LeaseTaskEntity findByBusinessKey(String queue, String businessKey) throws SQLException {
        List<Entity> rows = db.query(
                "SELECT " + COLUMNS + " FROM " + TABLE_NAME + " WHERE queue_name = ? AND business_key = ?",
                queue,
                businessKey);
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
        // 对应 UNION ALL 的第一部分：查找所有 READY 状态且已过可见时间的任务
        for (LeaseSubscription subscription : subscriptions) {
            params.add(subscription.getQueue());
        }
        params.add(now);
        // 对应 UNION ALL 的第二部分：查找所有 RUNNING 状态且租约已过期的任务（故障接管）
        for (LeaseSubscription subscription : subscriptions) {
            params.add(subscription.getQueue());
        }
        params.add(now);
        params.add(limit);
        // 委托方言构建具体的 SQL 结构，通常利用 UNION ALL 分别命中 READY 和 RUNNING 的专用索引
        String sql = dialect.buildAcquireCandidateSql(TABLE_NAME, COLUMNS, subscriptions.size());
        return toEntities(db.query(sql, params.toArray()));
    }

    /**
     * 尝试原子性抢占租约（核心乐观锁实现）
     * <p>
     * 该 SQL 确保只有满足以下条件之一的任务才能被抢占：
     * 1. 任务处于 READY 状态且已过可见时间（visible_at）。
     * 2. 任务处于 RUNNING 状态但租约已过期（lease_expires_at），即原持有节点疑似宕机或执行超时。
     * <p>
     * 同时引入了 `version` 校验，确保抢占操作是基于查找到的那个版本的快照，避免并发场景下的状态漂移。
     *
     * @param taskId          任务 ID
     * @param workerId        抢占该租约的工作节点 ID
     * @param leaseToken      本次授权的唯一令牌
     * @param leaseExpiresAt  设定的租约过期时间戳
     * @param now             当前时间戳，用于可见性与过期判定
     * @param expectedVersion 期待的行版本号，用于乐观锁冲突检测
     * @return 更新行数，1 表示抢占成功，0 表示已被其他节点抢占
     * @throws SQLException SQL 异常
     */
    public int tryAcquire(String taskId,
                          String workerId,
                          String leaseToken,
                          long leaseExpiresAt,
                          long now,
                          long expectedVersion)
            throws SQLException {
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET state = ?, worker_id = ?, lease_token = ?, lease_expires_at = ?, "
                        + "delivery_count = delivery_count + 1, updated_at = ?, version = version + 1 "
                        + "WHERE task_id = ? "
                        + "AND version = ? "
                        + "AND ((state = ? AND visible_at <= ?) OR (state = ? AND lease_expires_at <= ?))",
                LeaseTaskState.RUNNING.name(),
                workerId,
                leaseToken,
                leaseExpiresAt,
                now,
                taskId,
                expectedVersion,
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

        Entity entity = newEntity(now, safeRequest);

        return db.execute(
                "UPDATE " + TABLE_NAME + " SET " + buildUpdateAssignments(entity)
                        + " WHERE task_id = ? AND state = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                buildRuntimeReleaseParams(entity, taskId, workerId, leaseToken, now));
    }

    private Entity newEntity(long now, LeaseCloseRequest safeRequest) {
        Entity entity = Entity.create(TABLE_NAME);
        entity.set("state", LeaseTaskState.CLOSED.name());
        applyCloseRequest(entity, safeRequest);
        entity.set("worker_id", null);
        entity.set("lease_token", null);
        entity.set("lease_expires_at", 0);
        entity.set("updated_at", now);
        entity.set("version", SqlExpression.increment("version"));
        return entity;
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
                "UPDATE " + TABLE_NAME + " SET lease_expires_at = ?, updated_at = ?, version = version + 1 "
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
        entity.set("version", SqlExpression.increment("version"));
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
        Entity entity = Entity.create(TABLE_NAME);
        entity.set("state", LeaseTaskState.READY.name());
        entity.set("outcome", null);
        entity.set("failure_reason", null);
        entity.set("error_message", null);
        entity.set("visible_at", visibleAt);
        entity.set("worker_id", null);
        entity.set("lease_token", null);
        entity.set("lease_expires_at", 0);
        entity.set("updated_at", now);
        entity.set("version", SqlExpression.increment("version"));

        return db.execute(
                "UPDATE " + TABLE_NAME + " SET " + buildUpdateAssignments(entity)
                        + " WHERE " + WHERE_UNLOCKED_OR_EXPIRED,
                buildAdminWhereParams(entity, taskId, now));
    }

    /**
     * 管理面关闭任务
     */
    public int close(String taskId, LeaseCloseRequest request, long now) throws SQLException {
        LeaseCloseRequest safeRequest = request == null
                ? LeaseCloseRequest.cancelled(null)
                : request.normalizeForAdmin();

        Entity entity = newEntity(now, safeRequest);

        return db.execute(
                "UPDATE " + TABLE_NAME + " SET " + buildUpdateAssignments(entity)
                        + " WHERE " + WHERE_UNLOCKED_OR_EXPIRED,
                buildAdminWhereParams(entity, taskId, now));
    }

    /**
     * 将失败任务重新放入队列
     */
    public int requeueFailed(String taskId, long visibleAt, long now) throws SQLException {
        Entity entity = Entity.create(TABLE_NAME);
        entity.set("state", LeaseTaskState.READY.name());
        entity.set("outcome", null);
        entity.set("failure_reason", null);
        entity.set("error_message", null);
        entity.set("visible_at", visibleAt);
        entity.set("worker_id", null);
        entity.set("lease_token", null);
        entity.set("lease_expires_at", 0);
        entity.set("updated_at", now);
        entity.set("version", SqlExpression.increment("version"));

        List<Object> params = collectEntityParams(entity);
        params.add(taskId);
        params.add(LeaseTaskState.CLOSED.name());
        params.add(LeaseTaskOutcome.FAILED.name());

        return db.execute(
                "UPDATE " + TABLE_NAME + " SET " + buildUpdateAssignments(entity)
                        + " WHERE task_id = ? AND state = ? AND outcome = ?",
                params.toArray());
    }

    /**
     * 更新任务属性（运维接口）
     *
     * @param request 更新请求
     * @param now     当前时间
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int update(LeaseUpdateRequest request, long now) throws SQLException {
        Entity entity = Entity.create(TABLE_NAME);
        applyUpdateRequest(entity, request);
        if (entity.isEmpty()) {
            return 0;
        }
        entity.set("updated_at", now);
        entity.set("version", SqlExpression.increment("version"));
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET " + buildUpdateAssignments(entity)
                        + " WHERE " + WHERE_UNLOCKED_OR_EXPIRED,
                buildAdminWhereParams(entity, request.getTaskId(), now));
    }

    public int updateAndReschedule(
            LeaseUpdateRequest request,
            long visibleAt,
            long now) throws SQLException {
        Entity entity = Entity.create(TABLE_NAME);
        entity.set("state", LeaseTaskState.READY.name());
        entity.set("outcome", null);
        entity.set("failure_reason", null);
        entity.set("error_message", null);
        entity.set("visible_at", visibleAt);
        entity.set("worker_id", null);
        entity.set("lease_token", null);
        entity.set("lease_expires_at", 0);
        applyUpdateRequest(entity, request);
        entity.set("updated_at", now);
        entity.set("version", SqlExpression.increment("version"));
        return db.execute(
                "UPDATE " + TABLE_NAME + " SET " + buildUpdateAssignments(entity)
                        + " WHERE " + WHERE_UNLOCKED_OR_EXPIRED,
                buildAdminWhereParams(entity, request.getTaskId(), now));
    }

    private void applyCloseRequest(Entity entity, LeaseCloseRequest request) {
        if (request.getOutcome() == LeaseTaskOutcome.FAILED) {
            entity.set("failure_reason", request.getFailureReason().name());
            // 使用 SqlExpression 标记对象，在构建 UPDATE 语句时生成 "failure_count = failure_count + 1" 表达式
            entity.set("failure_count", SqlExpression.increment("failure_count"));
        }
        entity.set("outcome", request.getOutcome().name());
        entity.set("error_message", request.getErrorMessage());

        if (request.getPayload() != null) {
            entity.set("payload", request.getPayload());
        }
        if (!request.getAttributes().isEmpty()) {
            entity.set("attributes_json", jsonCodec.toJson(request.getAttributes()));
        }
    }

    private void applyUpdateRequest(Entity entity, LeaseUpdateRequest request) {
        if (request.getTaskType() != null) {
            entity.set("task_type", request.getTaskType());
        }
        if (request.getPayload() != null) {
            entity.set("payload", request.getPayload());
        }
        if (request.getPriority() != null) {
            entity.set("priority", request.getPriority());
        }
        if (request.getAttributes() != null && !request.getAttributes().isEmpty()) {
            entity.set("attributes_json", jsonCodec.toJson(request.getAttributes()));
        }
    }

    private String buildUpdateAssignments(Entity entity) {
        StringBuilder sql = new StringBuilder();
        for (String field : entity.keySet()) {
            if (sql.length() > 0) {
                sql.append(", ");
            }
            Object value = entity.get(field);
            if (value instanceof SqlExpression) {
                sql.append(field).append(" = ").append(((SqlExpression) value).getExpression());
            } else {
                sql.append(field).append(" = ?");
            }
        }
        return sql.toString();
    }

    /**
     * 从 Entity 中提取所有非 SQL 表达式的字段值，用于构建 UPDATE 语句的参数列表
     */
    private List<Object> collectEntityParams(Entity entity) {
        List<Object> params = new ArrayList<Object>(entity.size());
        for (String field : entity.keySet()) {
            Object value = entity.get(field);
            if (!(value instanceof SqlExpression)) {
                params.add(value);
            }
        }
        return params;
    }

    /**
     * 构建管理面 WHERE 子句参数（对应 WHERE_UNLOCKED_OR_EXPIRED）
     */
    private Object[] buildAdminWhereParams(Entity entity, String taskId, long now) {
        List<Object> params = collectEntityParams(entity);
        params.add(taskId);
        params.add(LeaseTaskState.CLOSED.name());
        params.add(LeaseTaskState.RUNNING.name());
        params.add(now);
        return params.toArray();
    }

    /**
     * 构建运行时释放操作的 WHERE 子句参数
     */
    private Object[] buildRuntimeReleaseParams(
            Entity entity,
            String taskId,
            String workerId,
            String leaseToken,
            long now) {
        List<Object> params = collectEntityParams(entity);
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

        StringBuilder whereSql = new StringBuilder(" WHERE 1=1");
        buildWhereFromQuery(whereSql, params, safeRequest);

        String sql = "SELECT " + COLUMNS + " FROM " + TABLE_NAME + whereSql + dialect.buildQuerySuffix();

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
        List<Object> params = new ArrayList<Object>();

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total FROM ").append(TABLE_NAME).append(" WHERE 1=1");
        buildWhereFromQuery(sql, params, safeRequest);

        List<Entity> rows = db.query(sql.toString(), params.toArray());
        if (rows.isEmpty()) {
            return 0L;
        }
        Number total = (Number) rows.get(0).get("total");
        return total == null ? 0L : total.longValue();
    }

    /**
     * 根据查询请求构建 WHERE 过滤条件
     * <p>
     * 该方法是 query 和 count 共用的 WHERE 构建逻辑，确保过滤条件只维护在一处。
     */
    private void buildWhereFromQuery(StringBuilder sql, List<Object> params, LeaseQueryRequest request) {
        if (request.getQueue() != null) {
            sql.append(" AND queue_name = ?");
            params.add(request.getQueue());
        }
        if (request.getTaskType() != null) {
            sql.append(" AND task_type = ?");
            params.add(request.getTaskType());
        }
        if (!request.getStates().isEmpty()) {
            sql.append(" AND state IN (").append(SqlExpression.placeholders(request.getStates().size())).append(")");
            for (LeaseTaskState state : request.getStates()) {
                params.add(state.name());
            }
        }
        if (!request.getOutcomes().isEmpty()) {
            sql.append(" AND outcome IN (").append(SqlExpression.placeholders(request.getOutcomes().size())).append(")");
            for (LeaseTaskOutcome outcome : request.getOutcomes()) {
                params.add(outcome.name());
            }
        }
        if (!request.getFailureReasons().isEmpty()) {
            sql.append(" AND failure_reason IN (").append(SqlExpression.placeholders(request.getFailureReasons().size()))
                    .append(")");
            for (LeaseTaskFailureReason failureReason : request.getFailureReasons()) {
                params.add(failureReason.name());
            }
        }
        if (request.getWorkerId() != null) {
            sql.append(" AND worker_id = ?");
            params.add(request.getWorkerId());
        }
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
                .businessKey(row.getStr("business_key"))
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
                .version(Convert.toLong(row.get("version"), 0L))
                .errorMessage(row.getStr("error_message"))
                .attributes(jsonCodec.fromJson(row.getStr("attributes_json")))
                .build();
    }
}
