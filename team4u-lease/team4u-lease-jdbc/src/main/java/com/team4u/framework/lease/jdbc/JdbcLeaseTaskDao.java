package com.team4u.framework.lease.jdbc;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.base.util.JdbcUtil;
import com.team4u.framework.base.util.SqlExpression;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.jdbc.codec.LeaseJsonCodec;
import com.team4u.framework.lease.jdbc.dialect.LeaseDbDialect;
import com.team4u.framework.lease.model.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;

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
    public static final String COLUMNS = "task_id, task_group, task_type, payload, business_key, state, outcome, failure_reason, "
            + "priority, delivery_count, failure_count, worker_id, lease_token, lease_expires_at, visible_at, "
            + "created_at, updated_at, version, error_message, attributes_json";
    /**
     * 通用的管理面操作过滤条件占位符
     * 用于确保状态非 CLOSED，且如果处于 RUNNING 状态则必须已过期
     */
    private static final String WHERE_UNLOCKED_OR_EXPIRED = "task_id = ? AND state <> ? AND NOT (state = ? AND lease_expires_at >= ?)";
    private final DataSource dataSource;
    private final LeaseDbDialect dialect;
    private final LeaseJsonCodec jsonCodec;

    public JdbcLeaseTaskDao(DataSource dataSource, LeaseDbDialect dialect, LeaseJsonCodec jsonCodec) {
        this.dataSource = dataSource;
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
        String sql = "INSERT INTO " + TABLE_NAME + " (" + COLUMNS + ") VALUES (" + SqlExpression.placeholders(20) + ")";
        JdbcUtil.execute(dataSource, sql,
                entity.getTaskId(),
                entity.getTaskGroup(),
                entity.getTaskType(),
                entity.getPayload(),
                entity.getBusinessKey(),
                entity.getState().name(),
                entity.getOutcome() == null ? null : entity.getOutcome().name(),
                entity.getFailureReason() == null ? null : entity.getFailureReason().name(),
                entity.getPriority(),
                entity.getDeliveryCount(),
                entity.getFailureCount(),
                entity.getWorkerId(),
                entity.getLeaseToken(),
                entity.getLeaseExpiresAtMillis(),
                entity.getVisibleAtMillis(),
                entity.getCreatedAtMillis(),
                entity.getUpdatedAtMillis(),
                entity.getVersion(),
                entity.getErrorMessage(),
                jsonCodec.toJson(entity.getAttributes()));
    }

    /**
     * 根据任务 ID 查询任务
     *
     * @param taskId 任务 ID
     * @return 任务实体，不存在返回 null
     * @throws SQLException SQL 异常
     */
    public LeaseTaskEntity findById(String taskId) throws SQLException {
        List<Map<String, Object>> rows = JdbcUtil.query(dataSource,
                "SELECT " + COLUMNS + " FROM " + TABLE_NAME + " WHERE task_id = ?",
                taskId);
        return rows.isEmpty() ? null : toEntity(rows.get(0));
    }

    /**
     * 根据业务键查询任务
     *
     * @param taskGroup   任务组名
     * @param businessKey 业务键
     * @return 任务实体，不存在返回 null
     * @throws SQLException SQL 异常
     */
    public LeaseTaskEntity findByBusinessKey(String taskGroup, String businessKey) throws SQLException {
        List<Map<String, Object>> rows = JdbcUtil.query(dataSource,
                "SELECT " + COLUMNS + " FROM " + TABLE_NAME + " WHERE task_group = ? AND business_key = ?",
                taskGroup,
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
    public List<LeaseTaskEntity> findAcquirableTasks(Set<LeaseTaskGroupSubscription> subscriptions, long now, int limit)
            throws SQLException {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> params = new ArrayList<>();
        // 对应 UNION ALL 的第一部分：查找所有 READY 状态且已过可见时间的任务
        for (LeaseTaskGroupSubscription subscription : subscriptions) {
            params.add(subscription.getTaskGroup());
        }
        params.add(now);
        // 对应 UNION ALL 的第二部分：查找所有 RUNNING 状态且租约已过期的任务（故障接管）
        for (LeaseTaskGroupSubscription subscription : subscriptions) {
            params.add(subscription.getTaskGroup());
        }
        params.add(now);
        params.add(limit);
        // 委托方言构建具体的 SQL 结构，通常利用 UNION ALL 分别命中 READY 和 RUNNING 的专用索引
        String sql = dialect.buildAcquireCandidateSql(TABLE_NAME, COLUMNS, subscriptions.size());
        return toEntities(JdbcUtil.query(dataSource, sql, params.toArray()));
    }

    /**
     * 尝试原子性抢占租约（核心乐观锁实现）
     * <p>
     * 该 SQL 确保只有满足以下条件之一的任务才能 be 抢占：
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
        return JdbcUtil.execute(dataSource,
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
     */
    public int close(String taskId, String workerId, String leaseToken, LeaseCloseRequest request, long now)
            throws SQLException {
        LeaseCloseRequest safeRequest = request == null
                ? LeaseCloseRequest.succeeded()
                : request.normalizeForRuntime();

        Map<String, Object> entity = newEntityMap(now, safeRequest);

        List<Object> params = new ArrayList<>();
        String assignments = buildUpdateAssignments(entity, params);

        params.add(taskId);
        params.add(LeaseTaskState.RUNNING.name());
        params.add(workerId);
        params.add(leaseToken);
        params.add(now);

        return JdbcUtil.execute(dataSource,
                "UPDATE " + TABLE_NAME + " SET " + assignments
                        + " WHERE task_id = ? AND state = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                params.toArray());
    }

    private Map<String, Object> newEntityMap(long now, LeaseCloseRequest safeRequest) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("state", LeaseTaskState.CLOSED.name());
        applyCloseRequest(entity, safeRequest);
        entity.put("worker_id", null);
        entity.put("lease_token", null);
        entity.put("lease_expires_at", 0);
        entity.put("updated_at", now);
        entity.put("version", SqlExpression.increment("version"));
        return entity;
    }

    /**
     * 续延租约（心跳）
     *
     * @param taskId         任务 ID
     * @param workerId       当前持有租约的工作节点 ID
     * @param leaseToken     当前租约令牌
     * @param leaseExpiresAt 新的租约过期时间戳
     * @param now            当前时间戳
     * @return 更新行数，1 表示续约成功，0 表示续约失败
     * @throws SQLException SQL 异常
     */
    public int heartbeat(String taskId, String workerId, String leaseToken, long leaseExpiresAt, long now)
            throws SQLException {
        return JdbcUtil.execute(dataSource,
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
     * @param taskId       任务 ID
     * @param workerId     当前持有租约的工作节点 ID
     * @param leaseToken   当前租约令牌
     * @param visibleAt    下次可见时间戳
     * @param payload      任务载荷
     * @param attributes   任务属性
     * @param errorMessage 错误消息
     * @param now          当前时间戳
     * @return 更新行数，1 表示释放成功，0 表示释放失败
     * @throws SQLException SQL 异常
     */
    public int release(
            String taskId,
            String workerId,
            String leaseToken,
            long visibleAt,
            String payload,
            java.util.Map<String, String> attributes,
            String errorMessage,
            long now)
            throws SQLException {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("state", LeaseTaskState.READY.name());
        entity.put("outcome", null);
        entity.put("failure_reason", null);
        entity.put("visible_at", visibleAt);
        entity.put("worker_id", null);
        entity.put("lease_token", null);
        entity.put("lease_expires_at", 0);
        entity.put("error_message", errorMessage);
        entity.put("version", SqlExpression.increment("version"));
        if (payload != null) {
            entity.put("payload", payload);
        }
        if (attributes != null && !attributes.isEmpty()) {
            entity.put("attributes_json", jsonCodec.toJson(attributes));
        }
        entity.put("updated_at", now);

        List<Object> params = new ArrayList<>();
        String assignments = buildUpdateAssignments(entity, params);
        params.add(taskId);
        params.add(LeaseTaskState.RUNNING.name());
        params.add(workerId);
        params.add(leaseToken);
        params.add(now);

        return JdbcUtil.execute(dataSource,
                "UPDATE " + TABLE_NAME + " SET " + assignments
                        + " WHERE task_id = ? AND state = ? AND worker_id = ? AND lease_token = ? AND lease_expires_at >= ?",
                params.toArray());
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
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("state", LeaseTaskState.READY.name());
        entity.put("outcome", null);
        entity.put("failure_reason", null);
        entity.put("error_message", null);
        entity.put("visible_at", visibleAt);
        entity.put("worker_id", null);
        entity.put("lease_token", null);
        entity.put("lease_expires_at", 0);
        entity.put("updated_at", now);
        entity.put("version", SqlExpression.increment("version"));

        List<Object> params = new ArrayList<>();
        String assignments = buildUpdateAssignments(entity, params);
        return JdbcUtil.execute(dataSource,
                "UPDATE " + TABLE_NAME + " SET " + assignments + " WHERE " + WHERE_UNLOCKED_OR_EXPIRED,
                buildAdminWhereParams(params, taskId, now));
    }

    /**
     * 管理面关闭任务
     *
     * @param taskId  任务 ID
     * @param request 关闭请求
     * @param now     当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int close(String taskId, LeaseCloseRequest request, long now) throws SQLException {
        LeaseCloseRequest safeRequest = request == null
                ? LeaseCloseRequest.cancelled(null)
                : request.normalizeForAdmin();

        Map<String, Object> entity = newEntityMap(now, safeRequest);

        List<Object> params = new ArrayList<>();
        String assignments = buildUpdateAssignments(entity, params);
        return JdbcUtil.execute(dataSource,
                "UPDATE " + TABLE_NAME + " SET " + assignments + " WHERE " + WHERE_UNLOCKED_OR_EXPIRED,
                buildAdminWhereParams(params, taskId, now));
    }

    /**
     * 将失败任务重新放入队列
     *
     * @param taskId    任务 ID
     * @param visibleAt 下次可见时间戳
     * @param now       当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int rescheduleFailed(String taskId, long visibleAt, long now) throws SQLException {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("state", LeaseTaskState.READY.name());
        entity.put("outcome", null);
        entity.put("failure_reason", null);
        entity.put("error_message", null);
        entity.put("visible_at", visibleAt);
        entity.put("worker_id", null);
        entity.put("lease_token", null);
        entity.put("lease_expires_at", 0);
        entity.put("updated_at", now);
        entity.put("version", SqlExpression.increment("version"));

        List<Object> params = new ArrayList<>();
        String assignments = buildUpdateAssignments(entity, params);
        params.add(taskId);
        params.add(LeaseTaskState.CLOSED.name());
        params.add(LeaseTaskOutcome.FAILED.name());

        return JdbcUtil.execute(dataSource,
                "UPDATE " + TABLE_NAME + " SET " + assignments + " WHERE task_id = ? AND state = ? AND outcome = ?",
                params.toArray());
    }

    /**
     * 更新任务属性（运维接口）
     *
     * @param request 更新请求
     * @param now     当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int update(LeaseUpdateRequest request, long now) throws SQLException {
        Map<String, Object> entity = new LinkedHashMap<>();
        applyUpdateRequest(entity, request);
        if (entity.isEmpty()) {
            return 0;
        }
        entity.put("updated_at", now);
        entity.put("version", SqlExpression.increment("version"));

        List<Object> params = new ArrayList<>();
        String assignments = buildUpdateAssignments(entity, params);
        return JdbcUtil.execute(dataSource,
                "UPDATE " + TABLE_NAME + " SET " + assignments + " WHERE " + WHERE_UNLOCKED_OR_EXPIRED,
                buildAdminWhereParams(params, request.getTaskId(), now));
    }

    /**
     * 更新并重新调度任务
     *
     * @param request   更新请求
     * @param visibleAt 下次可见时间戳
     * @param now       当前时间戳
     * @return 更新行数
     * @throws SQLException SQL 异常
     */
    public int updateAndReschedule(
            LeaseUpdateRequest request,
            long visibleAt,
            long now) throws SQLException {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("state", LeaseTaskState.READY.name());
        entity.put("outcome", null);
        entity.put("failure_reason", null);
        entity.put("error_message", null);
        entity.put("visible_at", visibleAt);
        entity.put("worker_id", null);
        entity.put("lease_token", null);
        entity.put("lease_expires_at", 0);
        applyUpdateRequest(entity, request);
        entity.put("updated_at", now);
        entity.put("version", SqlExpression.increment("version"));

        List<Object> params = new ArrayList<>();
        String assignments = buildUpdateAssignments(entity, params);
        return JdbcUtil.execute(dataSource,
                "UPDATE " + TABLE_NAME + " SET " + assignments + " WHERE " + WHERE_UNLOCKED_OR_EXPIRED,
                buildAdminWhereParams(params, request.getTaskId(), now));
    }

    private void applyCloseRequest(Map<String, Object> entity, LeaseCloseRequest request) {
        if (request.getOutcome() == LeaseTaskOutcome.FAILED) {
            entity.put("failure_reason", request.getFailureReason().name());
            entity.put("failure_count", SqlExpression.increment("failure_count"));
        }
        entity.put("outcome", request.getOutcome().name());
        entity.put("error_message", request.getErrorMessage());

        if (request.getPayload() != null) {
            entity.put("payload", request.getPayload());
        }
        if (!request.getAttributes().isEmpty()) {
            entity.put("attributes_json", jsonCodec.toJson(request.getAttributes()));
        }
    }

    private void applyUpdateRequest(Map<String, Object> entity, LeaseUpdateRequest request) {
        if (request.getTaskType() != null) {
            entity.put("task_type", request.getTaskType());
        }
        if (request.getPayload() != null) {
            entity.put("payload", request.getPayload());
        }
        if (request.getPriority() != null) {
            entity.put("priority", request.getPriority());
        }
        if (request.getAttributes() != null && !request.getAttributes().isEmpty()) {
            entity.put("attributes_json", jsonCodec.toJson(request.getAttributes()));
        }
    }

    private String buildUpdateAssignments(Map<String, Object> entity, List<Object> params) {
        StringBuilder sql = new StringBuilder();
        for (Map.Entry<String, Object> entry : entity.entrySet()) {
            if (sql.length() > 0) {
                sql.append(", ");
            }
            String field = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof SqlExpression) {
                sql.append(field).append(" = ").append(((SqlExpression) value).getExpression());
            } else {
                sql.append(field).append(" = ?");
                params.add(value);
            }
        }
        return sql.toString();
    }

    /**
     * 构建管理面 WHERE 子句参数
     */
    private Object[] buildAdminWhereParams(List<Object> params, String taskId, long now) {
        params.add(taskId);
        params.add(LeaseTaskState.CLOSED.name());
        params.add(LeaseTaskState.RUNNING.name());
        params.add(now);
        return params.toArray();
    }

    /**
     * 分页查询任务
     *
     * @param request 查询请求
     * @return 任务分页结果
     * @throws SQLException SQL 异常
     */
    public LeaseTaskPage query(LeaseQueryRequest request) throws SQLException {
        LeaseQueryRequest safeRequest = request == null ? LeaseQueryRequest.builder().build() : request;
        List<Object> params = new ArrayList<>();

        StringBuilder whereSql = new StringBuilder(" WHERE 1=1");
        buildWhereFromQuery(whereSql, params, safeRequest);

        String sql = "SELECT " + COLUMNS + " FROM " + TABLE_NAME + whereSql + dialect.buildQuerySuffix();

        int page = Math.max(0, safeRequest.getPage());
        int pageSize = safeRequest.getPageSize() <= 0 ? 50 : safeRequest.getPageSize();
        params.add(pageSize);
        params.add(page * pageSize);

        List<LeaseTaskEntity> items = toEntities(JdbcUtil.query(dataSource, sql, params.toArray()));
        long total = count(safeRequest);

        List<LeaseTaskRecord> records = new ArrayList<>(items.size());
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
     * 统计满足条件的任务总数
     *
     * @param request 查询请求
     * @return 任务总数
     * @throws SQLException SQL 异常
     */
    public long count(LeaseQueryRequest request) throws SQLException {
        LeaseQueryRequest safeRequest = request == null ? LeaseQueryRequest.builder().build() : request;
        List<Object> params = new ArrayList<>();

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total FROM ").append(TABLE_NAME).append(" WHERE 1=1");
        buildWhereFromQuery(sql, params, safeRequest);

        List<Map<String, Object>> rows = JdbcUtil.query(dataSource, sql.toString(), params.toArray());
        if (rows.isEmpty()) {
            return 0L;
        }
        Number total = (Number) rows.get(0).get("total");
        return total == null ? 0L : total.longValue();
    }

    private void buildWhereFromQuery(StringBuilder sql, List<Object> params, LeaseQueryRequest request) {
        if (request.getTaskGroup() != null) {
            sql.append(" AND task_group = ?");
            params.add(request.getTaskGroup());
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

    private List<LeaseTaskEntity> toEntities(List<Map<String, Object>> rows) {
        List<LeaseTaskEntity> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private LeaseTaskEntity toEntity(Map<String, Object> row) {
        return LeaseTaskEntity.builder()
                .taskId((String) row.get("task_id"))
                .taskGroup((String) row.get("task_group"))
                .taskType((String) row.get("task_type"))
                .payload((String) row.get("payload"))
                .businessKey((String) row.get("business_key"))
                .state(LeaseTaskState.valueOf((String) row.get("state")))
                .outcome(row.get("outcome") == null ? null : LeaseTaskOutcome.valueOf((String) row.get("outcome")))
                .failureReason(row.get("failure_reason") == null ? null
                        : LeaseTaskFailureReason.valueOf((String) row.get("failure_reason")))
                .priority(ConvertUtil.toInt(row.get("priority"), 0))
                .deliveryCount(ConvertUtil.toInt(row.get("delivery_count"), 0))
                .failureCount(ConvertUtil.toInt(row.get("failure_count"), 0))
                .workerId((String) row.get("worker_id"))
                .leaseToken((String) row.get("lease_token"))
                .leaseExpiresAtMillis(ConvertUtil.toLong(row.get("lease_expires_at"), 0L))
                .visibleAtMillis(ConvertUtil.toLong(row.get("visible_at"), 0L))
                .createdAtMillis(ConvertUtil.toLong(row.get("created_at"), 0L))
                .updatedAtMillis(ConvertUtil.toLong(row.get("updated_at"), 0L))
                .version(ConvertUtil.toLong(row.get("version"), 0L))
                .errorMessage((String) row.get("error_message"))
                .attributes(jsonCodec.fromJson((String) row.get("attributes_json")))
                .build();
    }
}
