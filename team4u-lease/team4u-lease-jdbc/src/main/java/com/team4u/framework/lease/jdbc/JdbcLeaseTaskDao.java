package com.team4u.framework.lease.jdbc;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.base.jdbc.JdbcUtil;
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
 * Data access for the five-state lease_task table. Every transition is one conditional UPDATE.
 */
public class JdbcLeaseTaskDao {

    public static final String TABLE_NAME = "lease_task";
    public static final String COLUMNS = "task_id, queue_name, task_type, payload, deduplication_key, "
            + "status, priority, attempt_count, worker_id, lease_token, lease_expires_at, "
            + "visible_at, created_at, updated_at, version, error_message, attributes_json";

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
                + placeholders(COLUMN_COUNT) + ")";
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
        Update update = new Update();
        update.set("status", TaskStatus.RUNNING.name())
                .set("attempt_count", "attempt_count + 1", true)
                .set("worker_id", workerId)
                .set("lease_token", leaseToken)
                .set("lease_expires_at", leaseExpiresAt)
                .set("updated_at", now)
                .set("version", "version + 1", true)
                .where("task_id = ?", taskId)
                .where("queue_name = ?", subscription.getQueue())
                .whereIn("task_type", types)
                .where("version = ?", expectedVersion)
                .where("((status = ? AND visible_at <= ?) OR (status = ? AND lease_expires_at <= ?))",
                        TaskStatus.PENDING.name(), now, TaskStatus.RUNNING.name(), now);
        return update.execute();
    }

    public int heartbeat(String taskId, String workerId, String leaseToken,
                         long leaseExpiresAt, long now) throws SQLException {
        // A late heartbeat must never shorten a lease renewed by a newer heartbeat.
        return new Update()
                .set("lease_expires_at", "CASE WHEN " + leaseExpiresAt
                        + " > lease_expires_at THEN " + leaseExpiresAt
                        + " ELSE lease_expires_at END", true)
                .set("updated_at", now)
                .set("version", "version + 1", true)
                .where("task_id = ?", taskId)
                .where("status = ?", TaskStatus.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at > ?", now)
                .execute();
    }

    public int close(String taskId, String workerId, String leaseToken,
                     LeaseCompletion completion, long now) throws SQLException {
        Update update = new Update()
                .set("status", completion.getStatus().name())
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("error_message", completion.getErrorMessage())
                .set("updated_at", now)
                .set("version", "version + 1", true);
        applyOptionalPayloadAndAttributes(update, completion.getPayload(),
                completion.hasAttributes(), completion.getAttributes());
        return update
                .where("task_id = ?", taskId)
                .where("status = ?", TaskStatus.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at > ?", now)
                .execute();
    }

    public int release(String taskId, String workerId, String leaseToken, LeaseRetry retry,
                       long visibleAt, long now) throws SQLException {
        Update update = new Update()
                .set("status", TaskStatus.PENDING.name())
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("error_message", retry.getErrorMessage())
                .set("visible_at", visibleAt)
                .set("updated_at", now)
                .set("version", "version + 1", true);
        applyOptionalPayloadAndAttributes(update, retry.getPayload(), retry.hasAttributes(),
                retry.getAttributes());
        return update
                .where("task_id = ?", taskId)
                .where("status = ?", TaskStatus.RUNNING.name())
                .where("worker_id = ?", workerId)
                .where("lease_token = ?", leaseToken)
                .where("lease_expires_at > ?", now)
                .execute();
    }

    public int complete(String queueName, String taskId, LeaseCompletion completion, long now)
            throws SQLException {
        Update update = new Update()
                .set("status", completion.getStatus().name())
                .set("error_message", completion.getErrorMessage())
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("updated_at", now)
                .set("version", "version + 1", true);
        applyOptionalPayloadAndAttributes(update, completion.getPayload(),
                completion.hasAttributes(), completion.getAttributes());
        return update
                .where("task_id = ?", taskId)
                .where("queue_name = ?", queueName)
                .whereAdminAllowed(now)
                .execute();
    }

    public int reschedule(String queueName, String taskId, long visibleAt, long now)
            throws SQLException {
        return new Update()
                .set("status", TaskStatus.PENDING.name())
                .set("error_message", null)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("visible_at", visibleAt)
                .set("updated_at", now)
                .set("version", "version + 1", true)
                .where("task_id = ?", taskId)
                .where("queue_name = ?", queueName)
                .where("status = ? OR (status = ? AND lease_expires_at <= ?)",
                        TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(), now)
                .execute();
    }

    public int retryFailed(String queueName, String taskId, long visibleAt, long now)
            throws SQLException {
        return new Update()
                .set("status", TaskStatus.PENDING.name())
                .set("error_message", null)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("visible_at", visibleAt)
                .set("updated_at", now)
                .set("version", "version + 1", true)
                .where("task_id = ?", taskId)
                .where("queue_name = ?", queueName)
                .where("status = ?", TaskStatus.FAILED.name())
                .execute();
    }

    public int update(UpdateCommand command, long now) throws SQLException {
        Update update = new Update();
        applyUpdateFields(update, command);
        update.set("updated_at", now).set("version", "version + 1", true);
        return update.where("task_id = ?", command.getTaskId())
                .where("queue_name = ?", command.getQueue())
                .whereAdminAllowed(now)
                .execute();
    }

    public int updateAndReschedule(UpdateCommand command, long visibleAt, long now)
            throws SQLException {
        Update update = new Update()
                .set("status", TaskStatus.PENDING.name())
                .set("error_message", null)
                .set("worker_id", null)
                .set("lease_token", null)
                .set("lease_expires_at", null)
                .set("visible_at", visibleAt);
        applyUpdateFields(update, command);
        update.set("updated_at", now).set("version", "version + 1", true);
        return update.where("task_id = ?", command.getTaskId())
                .where("queue_name = ?", command.getQueue())
                .whereAdminAllowed(now)
                .execute();
    }

    public List<LeaseTaskEntity> query(String queueName, TaskQuery query) throws SQLException {
        Select select = new Select();
        select.where("queue_name = ?", queueName);
        if (query.getType() != null) {
            select.where("task_type = ?", query.getType());
        }
        if (query.getStatus() != null) {
            select.where("status = ?", query.getStatus().name());
        }
        if (query.getWorkerId() != null) {
            select.where("worker_id = ?", query.getWorkerId());
        }
        List<Object> params = new ArrayList<Object>(select.params);
        long offset = query.getPage() * (long) query.getPageSize();
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("query offset is too large: " + offset);
        }
        params.add(query.getPageSize());
        params.add(Long.valueOf(offset));
        return toEntities(JdbcUtil.query(dataSource, "SELECT " + COLUMNS + " FROM " + TABLE_NAME
                + select.sql + dialect.buildQuerySuffix(), params.toArray()));
    }

    public long count(String queueName, TaskQuery query) throws SQLException {
        Select select = new Select();
        select.where("queue_name = ?", queueName);
        if (query.getType() != null) {
            select.where("task_type = ?", query.getType());
        }
        if (query.getStatus() != null) {
            select.where("status = ?", query.getStatus().name());
        }
        if (query.getWorkerId() != null) {
            select.where("worker_id = ?", query.getWorkerId());
        }
        List<Map<String, Object>> rows = JdbcUtil.query(dataSource,
                "SELECT COUNT(*) AS total FROM " + TABLE_NAME + select.sql, select.params.toArray());
        if (rows.isEmpty() || rows.get(0).get("total") == null) {
            return 0L;
        }
        return ((Number) rows.get(0).get("total")).longValue();
    }

    private void applyUpdateFields(Update update, UpdateCommand command) {
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

    private void applyOptionalPayloadAndAttributes(Update update, String payload,
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
        Object expiresAt = row.get("lease_expires_at");
        return LeaseTaskEntity.builder()
                .taskId((String) row.get("task_id"))
                .queueName((String) row.get("queue_name"))
                .taskType((String) row.get("task_type"))
                .payload((String) row.get("payload"))
                .deduplicationKey((String) row.get("deduplication_key"))
                .status(TaskStatus.valueOf((String) row.get("status")))
                .priority(ConvertUtil.toInt(row.get("priority"), 0))
                .attemptCount(ConvertUtil.toInt(row.get("attempt_count"), 0))
                .workerId((String) row.get("worker_id"))
                .leaseToken((String) row.get("lease_token"))
                .leaseExpiresAt(expiresAt == null ? null
                        : Long.valueOf(ConvertUtil.toLong(expiresAt, 0L)))
                .visibleAt(ConvertUtil.toLong(row.get("visible_at"), 0L))
                .createdAt(ConvertUtil.toLong(row.get("created_at"), 0L))
                .updatedAt(ConvertUtil.toLong(row.get("updated_at"), 0L))
                .version(ConvertUtil.toLong(row.get("version"), 0L))
                .errorMessage((String) row.get("error_message"))
                .attributes(jsonCodec.fromJson((String) row.get("attributes_json")))
                .build();
    }

    private static String placeholders(int count) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        return sql.toString();
    }

    private static final int COLUMN_COUNT = 17;

    private final class Update {
        private final StringBuilder sql = new StringBuilder("UPDATE ").append(TABLE_NAME).append(" SET ");
        private final List<Object> params = new ArrayList<Object>();
        private boolean firstSet = true;
        private boolean whereStarted;

        Update set(String column, Object value) {
            return set(column, value, false);
        }

        Update set(String column, Object value, boolean expression) {
            if (!firstSet) {
                sql.append(", ");
            }
            firstSet = false;
            sql.append(column).append(" = ");
            if (expression) {
                sql.append(value);
            } else {
                sql.append("?");
                params.add(value);
            }
            return this;
        }

        Update where(String condition, Object... values) {
            if (!whereStarted) {
                sql.append(" WHERE ");
                whereStarted = true;
            } else {
                sql.append(" AND ");
            }
            sql.append(condition);
            for (Object value : values) {
                params.add(value);
            }
            return this;
        }

        Update whereIn(String column, List<String> values) {
            return where(column + " IN (" + placeholders(values.size()) + ")",
                    values.toArray());
        }

        Update whereAdminAllowed(long now) {
            return where("status NOT IN (?, ?, ?)", TaskStatus.SUCCEEDED.name(),
                    TaskStatus.FAILED.name(), TaskStatus.CANCELLED.name())
                    .where("NOT (status = ? AND lease_expires_at > ?)",
                            TaskStatus.RUNNING.name(), now);
        }

        int execute() throws SQLException {
            return JdbcUtil.execute(dataSource, sql.toString(), params.toArray());
        }
    }

    private static final class Select {
        private final StringBuilder sql = new StringBuilder(" WHERE 1=1");
        private final List<Object> params = new ArrayList<Object>();

        Select where(String condition, Object... values) {
            sql.append(" AND ").append(condition);
            for (Object value : values) {
                params.add(value);
            }
            return this;
        }
    }
}
