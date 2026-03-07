package com.team4u.framework.lease.jdbc.dialect;

/**
 * MySQL 的租赁数据库方言实现
 *
 * @author jay.wu
 */
public class MySqlLeaseDbDialect implements LeaseDbDialect {

    @Override
    public String buildAcquireCandidateSql(int queueCount) {
        return "SELECT task_id, queue_name, task_type, payload, status, priority, delivery_count, failure_count, "
                + "worker_id, lease_token, lease_expires_at, visible_at, created_at, updated_at, last_error, attributes_json "
                + "FROM lease_task "
                + "WHERE queue_name IN (" + placeholders(queueCount) + ") "
                + "AND ((status = 'SCHEDULED' AND visible_at <= ?) OR (status = 'LEASED' AND lease_expires_at <= ?)) "
                + "ORDER BY priority DESC, created_at ASC, task_id ASC "
                + "LIMIT ?";
    }

    @Override
    public String buildQuerySql(boolean filterQueue,
                                boolean filterTaskType,
                                int statusCount,
                                boolean filterWorkerId) {
        StringBuilder sql = new StringBuilder("SELECT task_id, queue_name, task_type, payload, status, priority, ")
                .append("delivery_count, failure_count, worker_id, lease_token, lease_expires_at, visible_at, ")
                .append("created_at, updated_at, last_error, attributes_json ")
                .append("FROM lease_task WHERE 1=1");
        if (filterQueue) {
            sql.append(" AND queue_name = ?");
        }
        if (filterTaskType) {
            sql.append(" AND task_type = ?");
        }
        if (statusCount > 0) {
            sql.append(" AND status IN (").append(placeholders(statusCount)).append(")");
        }
        if (filterWorkerId) {
            sql.append(" AND worker_id = ?");
        }
        sql.append(" ORDER BY created_at ASC, task_id ASC LIMIT ? OFFSET ?");
        return sql.toString();
    }

    protected String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("?");
        }
        return builder.toString();
    }
}
