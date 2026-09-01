package com.team4u.framework.lease.jdbc;

import com.team4u.framework.base.jdbc.JdbcUtil;
import com.team4u.framework.base.jdbc.SqlBuilder;
import com.team4u.framework.base.jdbc.SqlExpression;
import com.team4u.framework.base.jdbc.UpdateBuilder;
import com.team4u.framework.base.util.MapReader;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.jdbc.codec.LeaseJsonCodec;
import com.team4u.framework.lease.jdbc.dialect.LeaseDbDialect;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.TaskSubscription;
import com.team4u.framework.lease.spi.UpdateCommand;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * lease_task 表（五状态模型）的数据访问层
 * <p>
 * SQL 构建统一使用 base 的 {@link UpdateBuilder}/{@link SqlBuilder}/
 * {@link SqlExpression}，本类不再持有私有的 SQL 拼接设施。
 * 所有状态迁移均为单条条件 UPDATE（乐观并发：version + 1），
 * 迁移是否生效由受影响行数（0 或 1）判定。
 */
public class JdbcLeaseTaskDao {

    public static final String TABLE_NAME = "lease_task";
    public static final String COLUMNS = "task_id, queue_name, task_type, payload, deduplication_key, "
            + "status, priority, attempt_count, worker_id, lease_token, lease_expires_at, "
            + "visible_at, created_at, updated_at, version, error_message, attributes_json";

    private static final int COLUMN_COUNT = 17;

    private final DataSource dataSource;
    private final LeaseDbDialect dialect;
    private final LeaseJsonCodec jsonCodec;

    public JdbcLeaseTaskDao(DataSource dataSource, LeaseDbDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.jsonCodec = new LeaseJsonCodec();
    }

    public void insert(LeaseTaskEntity entity) throws SQLException {
        String sql = "INSERT INTO " + TABLE_NAME + " (" + COLUMNS + ") VALUES ("
                + SqlExpression.placeholders(COLUMN_COUNT) + ")";
        JdbcUtil.execute(dataSource, sql,
                entity.getTaskId(),
                entity.getQueueName(),
                entity.getTaskType(),
                entity.getPayload(),
                entity.getDeduplicationKey(),
                entity.getStatus().name(),
                entity.getPriority(),
                entity.getAttemptCount(),
                entity.getWorkerId(),
                entity.getLeaseToken(),
                entity.getLeaseExpiresAt(),
                entity.getVisibleAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion(),
                entity.getErrorMessage(),
                jsonCodec.toJson(entity.getAttributes()));
    }

    public LeaseTaskEntity findById(String queueName, String taskId) throws SQLException {
        List<LeaseTaskEntity> entities = toEntities(JdbcUtil.query(dataSource,
                "SELECT " + COLUMNS + " FROM " + TABLE_NAME + " WHERE queue_name = ? AND task_id = ?",
                queueName, taskId));
        return entities.isEmpty() ? null : entities.get(0);
    }

    public LeaseTaskEntity findById(String taskId) throws SQLException {
        List<LeaseTaskEntity> entities = toEntities(JdbcUtil.query(dataSource,
                "SELECT " + COLUMNS + " FROM " + TABLE_NAME + " WHERE task_id = ?", taskId));
        return entities.isEmpty() ? null : entities.get(0);
    }

    public LeaseTaskEntity findByLease(String queueName, String taskId, String workerId,
                                        String leaseToken, long version) throws SQLException {
        List<LeaseTaskEntity> entities = toEntities(JdbcUtil.query(dataSource,
                "SELECT " + COLUMNS + " FROM " + TABLE_NAME
                        + " WHERE queue_name = ? AND task_id = ? AND status = ?"
                        + " AND worker_id = ? AND lease_token = ? AND version = ?",
                queueName, taskId, TaskStatus.RUNNING.name(), workerId, leaseToken,
                Long.valueOf(version)));
        return entities.isEmpty() ? null : entities.get(0);
    }
    public LeaseTaskEntity findByDeduplicationKey(String queueName, String taskType,
                                                  String deduplicationKey) throws SQLException {
        List<LeaseTaskEntity> entities = toEntities(JdbcUtil.query(dataSource,
                "SELECT " + COLUMNS + " FROM " + TABLE_NAME
                        + " WHERE queue_name = ? AND task_type = ? AND deduplication_key = ?",
                queueName, taskType, deduplicationKey));
        return entities.isEmpty() ? null : entities.get(0);
    }

    public List<LeaseTaskEntity> findAcquirableTasks(TaskSubscription subscription, long now,
                                                      int limit) throws SQLException {
        List<String> types = new ArrayList<String>(new LinkedHashSet<String>(
                subscription.getTaskTypes()));
        String sql = dialect.buildAcquireCandidateSql(TABLE_NAME, COLUMNS, types.size());
        List<Object> params = new ArrayList<Object>();
        params.add(subscription.getQueue());
        params.addAll(types);
        params.add(TaskStatus.PENDING.name());
        params.add(now);
        params.add(subscription.getQueue());
        params.addAll(types);
        params.add(TaskStatus.RUNNING.name());
        params.add(now);
        params.add(limit);
        return toEntities(JdbcUtil.query(dataSource, sql, params.toArray()));
    }

    public int tryAcquire(TaskSubscription subscription, String taskId, String workerId,
                          String leaseToken, long leaseExpiresAt, long now,
                          long expectedVersion) throws SQLException {
        List<String> types = new ArrayList<String>(new LinkedHashSet<String>(
                subscription.getTaskTypes()));
        UpdateBuilder update = new UpdateBuilder(TABLE_NAME);
        update.set("status", TaskStatus.RUNNING.name())
                .setExpression("attempt_count", "attempt_count + 1")
                .set("worker_id", workerId)
                .set("lease_token", leaseToken)
                .set("lease_expires_at", leaseExpiresAt)
                .set("updated_at", now)
                .setExpression("version", "version + 1")
                .where("task_id = ?", taskId)
                .where("queue_name = ?", subscription.getQueue())
                .where(taskTypeIn(types), types.toArray())
                .where("version = ?", expectedVersion)
                .where("((status = ? AND visible_at <= ?) OR (status = ? AND lease_expires_at <= ?))",
                        TaskStatus.PENDING.name(), now, TaskStatus.RUNNING.name(), now);
        return execute(update);
    }

    public int heartbeat(String taskId, String workerId, String leaseToken,
                         long leaseExpiresAt, long now) throws SQLException {
        // A late heartbeat must never shorten a lease renewed by a newer heartbeat.
        return execute(new UpdateBuilder(TABLE_NAME)
                .setExpression("lease_expires_at", "CASE WHEN " + leaseExpiresAt
                        + " > lease_expires_at THEN " + leaseExpiresAt
                        + " ELSE lease_expires_at END")
                .set("updated_at", now)
                .setExpression("version", "version + 1")
                .where("task_id = ?", taskId)
                .where("status = ?", TaskStatus.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at > ?", now));
    }

    public int close(String taskId, String workerId, String leaseToken,
                     LeaseCompletion completion, long now) throws SQLException {
        UpdateBuilder update = new UpdateBuilder(TABLE_NAME)
                .set("status", completion.getStatus().name())
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("error_message", completion.getErrorMessage())
                .set("updated_at", now)
                .setExpression("version", "version + 1");
        applyOptionalPayloadAndAttributes(update, completion.getPayload(),
                completion.hasAttributes(), completion.getAttributes());
        return execute(update
                .where("task_id = ?", taskId)
                .where("status = ?", TaskStatus.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at > ?", now));
    }

    public int release(String taskId, String workerId, String leaseToken, LeaseRetry retry,
                       long visibleAt, long now) throws SQLException {
        UpdateBuilder update = new UpdateBuilder(TABLE_NAME)
                .set("status", TaskStatus.PENDING.name())
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("error_message", retry.getErrorMessage())
                .set("visible_at", visibleAt)
                .set("updated_at", now)
                .setExpression("version", "version + 1");
        applyOptionalPayloadAndAttributes(update, retry.getPayload(), retry.hasAttributes(),
                retry.getAttributes());
        return execute(update
                .where("task_id = ?", taskId)
                .where("status = ?", TaskStatus.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at > ?", now));
    }

    public int complete(String queueName, String taskId, LeaseCompletion completion, long now)
            throws SQLException {
        UpdateBuilder update = new UpdateBuilder(TABLE_NAME)
                .set("status", completion.getStatus().name())
                .set("error_message", completion.getErrorMessage())
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("updated_at", now)
                .setExpression("version", "version + 1");
        applyOptionalPayloadAndAttributes(update, completion.getPayload(),
                completion.hasAttributes(), completion.getAttributes());
        return execute(update
                .where("task_id = ?", taskId)
                .where("queue_name = ?", queueName)
                .where(ADMIN_ALLOWED_CONDITION,
                        TaskStatus.SUCCEEDED.name(), TaskStatus.FAILED.name(),
                        TaskStatus.CANCELLED.name())
                .where("NOT (status = ? AND lease_expires_at > ?)",
                        TaskStatus.RUNNING.name(), now));
    }

    public int reschedule(String queueName, String taskId, long visibleAt, long now)
            throws SQLException {
        return execute(new UpdateBuilder(TABLE_NAME)
                .set("status", TaskStatus.PENDING.name())
                .set("error_message", null)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("visible_at", visibleAt)
                .set("updated_at", now)
                .setExpression("version", "version + 1")
                .where("task_id = ?", taskId)
                .where("queue_name = ?", queueName)
                .where("status = ? OR (status = ? AND lease_expires_at <= ?)",
                        TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(), now));
    }

    public int retryFailed(String queueName, String taskId, long visibleAt, long now)
            throws SQLException {
        return execute(new UpdateBuilder(TABLE_NAME)
                .set("status", TaskStatus.PENDING.name())
                .set("error_message", null)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("visible_at", visibleAt)
                .set("updated_at", now)
                .setExpression("version", "version + 1")
                .where("task_id = ?", taskId)
                .where("queue_name = ?", queueName)
                .where("status = ?", TaskStatus.FAILED.name()));
    }

    public int update(UpdateCommand command, long now) throws SQLException {
        UpdateBuilder update = new UpdateBuilder(TABLE_NAME);
        applyUpdateFields(update, command);
        update.set("updated_at", now).setExpression("version", "version + 1");
        return execute(whereAdminAllowed(update
                .where("task_id = ?", command.getTaskId())
                .where("queue_name = ?", command.getQueue()), now));
    }

    public int updateAndReschedule(UpdateCommand command, long visibleAt, long now)
            throws SQLException {
        UpdateBuilder update = new UpdateBuilder(TABLE_NAME)
                .set("status", TaskStatus.PENDING.name())
                .set("error_message", null)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("visible_at", visibleAt);
        applyUpdateFields(update, command);
        update.set("updated_at", now).setExpression("version", "version + 1");
        return execute(whereAdminAllowed(update
                .where("task_id = ?", command.getTaskId())
                .where("queue_name = ?", command.getQueue()), now));
    }

    public List<LeaseTaskEntity> query(String queueName, TaskQuery query) throws SQLException {
        SqlBuilder where = queryWhere(queueName, query);
        List<Object> params = new ArrayList<Object>();
        for (Object param : where.getParams()) {
            params.add(param);
        }
        long offset = query.getPage() * (long) query.getPageSize();
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("query offset is too large: " + offset);
        }
        params.add(query.getPageSize());
        params.add(Long.valueOf(offset));
        return toEntities(JdbcUtil.query(dataSource, "SELECT " + COLUMNS + " FROM " + TABLE_NAME
                + where.getSql() + dialect.buildQuerySuffix(), params.toArray()));
    }

    public long count(String queueName, TaskQuery query) throws SQLException {
        SqlBuilder where = queryWhere(queueName, query);
        List<Map<String, Object>> rows = JdbcUtil.query(dataSource,
                "SELECT COUNT(*) AS total FROM " + TABLE_NAME + where.getSql(), where.getParams());
        if (rows.isEmpty() || rows.get(0).get("total") == null) {
            return 0L;
        }
        return ((Number) rows.get(0).get("total")).longValue();
    }

    private static final String ADMIN_ALLOWED_CONDITION =
            "status NOT IN (?, ?, ?)";

    private static UpdateBuilder whereAdminAllowed(UpdateBuilder update, long now) {
        return update.where(ADMIN_ALLOWED_CONDITION,
                TaskStatus.SUCCEEDED.name(), TaskStatus.FAILED.name(),
                TaskStatus.CANCELLED.name())
                .where("NOT (status = ? AND lease_expires_at > ?)",
                        TaskStatus.RUNNING.name(), now);
    }

    private static String taskTypeIn(List<String> types) {
        return "task_type IN (" + SqlExpression.placeholders(types.size()) + ")";
    }

    private static SqlBuilder queryWhere(String queueName, TaskQuery query) {
        SqlBuilder where = new SqlBuilder(" WHERE 1=1");
        where.append(" AND queue_name = ?", queueName);
        if (query.getType() != null) {
            where.append(" AND task_type = ?", query.getType());
        }
        if (query.getStatus() != null) {
            where.append(" AND status = ?", query.getStatus().name());
        }
        if (query.getWorkerId() != null) {
            where.append(" AND worker_id = ?", query.getWorkerId());
        }
        return where;
    }

    private int execute(UpdateBuilder update) throws SQLException {
        return JdbcUtil.execute(dataSource, update.getSql(), update.getParams());
    }

    private void applyUpdateFields(UpdateBuilder update, UpdateCommand command) {
        if (command.getTaskType() != null) {
            update.set("task_type", command.getTaskType());
        }
        if (command.getPayload() != null) {
            update.set("payload", command.getPayload());
        }
        if (command.getPriority() != null) {
            update.set("priority", command.getPriority());
        }
        if (command.hasAttributes()) {
            update.set("attributes_json", jsonCodec.toJson(command.getAttributes()));
        }
    }

    private void applyOptionalPayloadAndAttributes(UpdateBuilder update, String payload,
                                                   boolean attributesPresent,
                                                   Map<String, String> attributes) {
        if (payload != null) {
            update.set("payload", payload);
        }
        if (attributesPresent) {
            update.set("attributes_json", jsonCodec.toJson(attributes));
        }
    }

    private List<LeaseTaskEntity> toEntities(List<Map<String, Object>> rows) {
        List<LeaseTaskEntity> entities = new ArrayList<LeaseTaskEntity>(rows.size());
        for (Map<String, Object> row : rows) {
            entities.add(toEntity(row));
        }
        return entities;
    }

    private LeaseTaskEntity toEntity(Map<String, Object> row) {
        MapReader reader = MapReader.of(row);
        return LeaseTaskEntity.builder()
                .taskId(reader.getString("task_id"))
                .queueName(reader.getString("queue_name"))
                .taskType(reader.getString("task_type"))
                .payload(reader.getString("payload"))
                .deduplicationKey(reader.getString("deduplication_key"))
                .status(reader.getEnum(TaskStatus.class, "status"))
                .priority(reader.getInt("priority", 0))
                .attemptCount(reader.getInt("attempt_count", 0))
                .workerId(reader.getString("worker_id"))
                .leaseToken(reader.getString("lease_token"))
                .leaseExpiresAt(reader.getLong("lease_expires_at"))
                .visibleAt(reader.getLong("visible_at", 0L))
                .createdAt(reader.getLong("created_at", 0L))
                .updatedAt(reader.getLong("updated_at", 0L))
                .version(reader.getLong("version", 0L))
                .errorMessage(reader.getString("error_message"))
                .attributes(jsonCodec.fromJson(reader.getString("attributes_json")))
                .build();
    }
}
