package com.team4u.framework.lease.jdbc;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.base.jdbc.InsertBuilder;
import com.team4u.framework.base.jdbc.JdbcUtil;
import com.team4u.framework.base.jdbc.SqlBuilder;
import com.team4u.framework.base.jdbc.UpdateBuilder;
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
        InsertBuilder builder = new InsertBuilder(TABLE_NAME)
                .column("task_id", entity.getTaskId())
                .column("task_group", entity.getTaskGroup())
                .column("task_type", entity.getTaskType())
                .column("payload", entity.getPayload())
                .column("business_key", entity.getBusinessKey())
                .column("state", entity.getState().name())
                .column("outcome", entity.getOutcome() == null ? null : entity.getOutcome().name())
                .column("failure_reason", entity.getFailureReason() == null ? null : entity.getFailureReason().name())
                .column("priority", entity.getPriority())
                .column("delivery_count", entity.getDeliveryCount())
                .column("failure_count", entity.getFailureCount())
                .column("worker_id", entity.getWorkerId())
                .column("lease_token", entity.getLeaseToken())
                .column("lease_expires_at", entity.getLeaseExpiresAtMillis())
                .column("visible_at", entity.getVisibleAtMillis())
                .column("created_at", entity.getCreatedAtMillis())
                .column("updated_at", entity.getUpdatedAtMillis())
                .column("version", entity.getVersion())
                .column("error_message", entity.getErrorMessage())
                .column("attributes_json", jsonCodec.toJson(entity.getAttributes()));

        JdbcUtil.execute(dataSource, builder.getSql(), builder.getParams());
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
        SqlBuilder sb = new SqlBuilder("UPDATE ").append(TABLE_NAME)
                .append(" SET state = ?, worker_id = ?, lease_token = ?, lease_expires_at = ?, ",
                        LeaseTaskState.RUNNING.name(), workerId, leaseToken, leaseExpiresAt)
                .append("delivery_count = delivery_count + 1, updated_at = ?, version = version + 1 ", now)
                .append("WHERE task_id = ? ", taskId)
                .append("AND version = ? ", expectedVersion)
                .append("AND ((state = ? AND visible_at <= ?) OR (state = ? AND lease_expires_at <= ?))",
                        LeaseTaskState.READY.name(), now, LeaseTaskState.RUNNING.name(), now);

        return JdbcUtil.execute(dataSource, sb.getSql(), sb.getParams());
    }

    /**
     * 关闭运行中的任务
     */
    public int close(String taskId, String workerId, String leaseToken, LeaseCloseRequest request, long now)
            throws SQLException {
        LeaseCloseRequest safeRequest = request == null
                ? LeaseCloseRequest.succeeded()
                : request.normalizeForRuntime();

        UpdateBuilder ub = new UpdateBuilder(TABLE_NAME);
        ub.set("state", LeaseTaskState.CLOSED.name());
        applyCloseRequest(ub, safeRequest);
        ub.set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", 0)
                .set("updated_at", now)
                .setExpression("version", "version + 1")
                .where("task_id = ?", taskId)
                .where("state = ?", LeaseTaskState.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at >= ?", now);

        return JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
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
        UpdateBuilder ub = new UpdateBuilder(TABLE_NAME)
                .set("lease_expires_at", leaseExpiresAt)
                .set("updated_at", now)
                .setExpression("version", "version + 1")
                .where("task_id = ?", taskId)
                .where("state = ?", LeaseTaskState.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at >= ?", now);

        return JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
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
        UpdateBuilder ub = new UpdateBuilder(TABLE_NAME)
                .set("state", LeaseTaskState.READY.name())
                .set("outcome", null)
                .set("failure_reason", null)
                .set("visible_at", visibleAt)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", 0)
                .set("error_message", errorMessage)
                .setExpression("version", "version + 1")
                .setIfNotNull("payload", payload)
                .set("updated_at", now);

        if (attributes != null && !attributes.isEmpty()) {
            ub.set("attributes_json", jsonCodec.toJson(attributes));
        }

        ub.where("task_id = ?", taskId)
                .where("state = ?", LeaseTaskState.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at >= ?", now);

        return JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
    }

    /**
     * 重新调度任务
     */
    public int reschedule(String taskId, long visibleAt, long now) throws SQLException {
        UpdateBuilder ub = new UpdateBuilder(TABLE_NAME)
                .set("state", LeaseTaskState.READY.name())
                .set("outcome", null)
                .set("failure_reason", null)
                .set("error_message", null)
                .set("visible_at", visibleAt)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", 0)
                .set("updated_at", now)
                .setExpression("version", "version + 1");

        applyAdminWhere(ub, taskId, now);
        return JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
    }

    /**
     * 管理面关闭任务
     */
    public int close(String taskId, LeaseCloseRequest request, long now) throws SQLException {
        LeaseCloseRequest safeRequest = request == null
                ? LeaseCloseRequest.cancelled(null)
                : request.normalizeForAdmin();

        UpdateBuilder ub = new UpdateBuilder(TABLE_NAME)
                .set("state", LeaseTaskState.CLOSED.name());

        applyCloseRequest(ub, safeRequest);
        ub.set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", 0)
                .set("updated_at", now)
                .setExpression("version", "version + 1");

        applyAdminWhere(ub, taskId, now);
        return JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
    }

    /**
     * 将失败任务重新放入队列
     */
    public int rescheduleFailed(String taskId, long visibleAt, long now) throws SQLException {
        UpdateBuilder ub = new UpdateBuilder(TABLE_NAME)
                .set("state", LeaseTaskState.READY.name())
                .set("outcome", null)
                .set("failure_reason", null)
                .set("error_message", null)
                .set("visible_at", visibleAt)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", 0)
                .set("updated_at", now)
                .setExpression("version", "version + 1")
                .where("task_id = ?", taskId)
                .where("state = ?", LeaseTaskState.CLOSED.name())
                .where("outcome = ?", LeaseTaskOutcome.FAILED.name());

        return JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
    }

    /**
     * 更新任务属性（运维接口）
     */
    public int update(LeaseUpdateRequest request, long now) throws SQLException {
        UpdateBuilder ub = new UpdateBuilder(TABLE_NAME);
        applyUpdateRequest(ub, request);
        ub.set("updated_at", now)
                .setExpression("version", "version + 1");

        applyAdminWhere(ub, request.getTaskId(), now);
        return JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
    }

    /**
     * 更新并重新调度任务
     */
    public int updateAndReschedule(
            LeaseUpdateRequest request,
            long visibleAt,
            long now) throws SQLException {
        UpdateBuilder ub = new UpdateBuilder(TABLE_NAME)
                .set("state", LeaseTaskState.READY.name())
                .set("outcome", null)
                .set("failure_reason", null)
                .set("error_message", null)
                .set("visible_at", visibleAt)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", 0);

        applyUpdateRequest(ub, request);
        ub.set("updated_at", now)
                .setExpression("version", "version + 1");

        applyAdminWhere(ub, request.getTaskId(), now);
        return JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
    }

    private void applyCloseRequest(UpdateBuilder ub, LeaseCloseRequest request) {
        if (request.getOutcome() == LeaseTaskOutcome.FAILED) {
            ub.set("failure_reason", request.getFailureReason().name());
            ub.setExpression("failure_count", "failure_count + 1");
        }
        ub.set("outcome", request.getOutcome().name());
        ub.set("error_message", request.getErrorMessage());

        if (request.getPayload() != null) {
            ub.set("payload", request.getPayload());
        }
        if (!request.getAttributes().isEmpty()) {
            ub.set("attributes_json", jsonCodec.toJson(request.getAttributes()));
        }
    }

    private void applyUpdateRequest(UpdateBuilder ub, LeaseUpdateRequest request) {
        if (request.getTaskType() != null) {
            ub.set("task_type", request.getTaskType());
        }
        if (request.getPayload() != null) {
            ub.set("payload", request.getPayload());
        }
        if (request.getPriority() != null) {
            ub.set("priority", request.getPriority());
        }
        if (request.getAttributes() != null && !request.getAttributes().isEmpty()) {
            ub.set("attributes_json", jsonCodec.toJson(request.getAttributes()));
        }
    }

    /**
     * 构建管理面 WHERE 条件
     */
    private void applyAdminWhere(UpdateBuilder ub, String taskId, long now) {
        ub.where("task_id = ?", taskId)
                .where("state <> ?", LeaseTaskState.CLOSED.name())
                .where("NOT (state = ? AND lease_expires_at >= ?)", LeaseTaskState.RUNNING.name(), now);
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

        SqlBuilder sb = new SqlBuilder(" WHERE 1=1");
        buildWhereFromQuery(sb, safeRequest);

        int page = Math.max(0, safeRequest.getPage());
        int pageSize = safeRequest.getPageSize() <= 0 ? 50 : safeRequest.getPageSize();

        String sql = "SELECT " + COLUMNS + " FROM " + TABLE_NAME + sb.getSql() + dialect.buildQuerySuffix();
        List<LeaseTaskEntity> items = toEntities(JdbcUtil.query(dataSource, sql,
                mergeParams(sb.getParams(), pageSize, page * pageSize)));

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

    private Object[] mergeParams(Object[] baseParams, Object... extraParams) {
        Object[] result = new Object[baseParams.length + extraParams.length];
        System.arraycopy(baseParams, 0, result, 0, baseParams.length);
        System.arraycopy(extraParams, 0, result, baseParams.length, extraParams.length);
        return result;
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
        SqlBuilder sb = new SqlBuilder("SELECT COUNT(*) AS total FROM ").append(TABLE_NAME).append(" WHERE 1=1");
        buildWhereFromQuery(sb, safeRequest);

        List<Map<String, Object>> rows = JdbcUtil.query(dataSource, sb.getSql(), sb.getParams());
        if (rows.isEmpty()) {
            return 0L;
        }
        Number total = (Number) rows.get(0).get("total");
        return total == null ? 0L : total.longValue();
    }

    private void buildWhereFromQuery(SqlBuilder sb, LeaseQueryRequest request) {
        sb.appendIfNotNull(" AND task_group = ?", request.getTaskGroup())
                .appendIfNotNull(" AND task_type = ?", request.getTaskType())
                .inIfNotEmpty(" AND state IN ", request.getStates().stream().map(Enum::name).collect(java.util.stream.Collectors.toList()))
                .inIfNotEmpty(" AND outcome IN ", request.getOutcomes().stream().map(Enum::name).collect(java.util.stream.Collectors.toList()))
                .inIfNotEmpty(" AND failure_reason IN ", request.getFailureReasons().stream().map(Enum::name).collect(java.util.stream.Collectors.toList()))
                .appendIfNotNull(" AND worker_id = ?", request.getWorkerId());
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
