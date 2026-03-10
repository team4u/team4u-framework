package com.team4u.framework.lease.jdbc.dialect;

/**
 * MySQL 的租赁数据库方言实现
 *
 * @author jay.wu
 */
public class MySqlLeaseDbDialect implements LeaseDbDialect {

    @Override
    public String buildAcquireCandidateSql(int queueCount) {
        return "WHERE queue_name IN (" + placeholders(queueCount) + ") "
                + "AND ((state = 'READY' AND visible_at <= ?) OR (state = 'RUNNING' AND lease_expires_at <= ?)) "
                + "ORDER BY priority DESC, created_at ASC, task_id ASC "
                + "LIMIT ?";
    }

    @Override
    public String buildQuerySql(boolean filterQueue,
                                boolean filterTaskType,
                                int stateCount,
                                int outcomeCount,
                                int reasonCount,
                                boolean filterWorkerId) {
        StringBuilder sql = new StringBuilder("WHERE 1=1");
        if (filterQueue) {
            sql.append(" AND queue_name = ?");
        }
        if (filterTaskType) {
            sql.append(" AND task_type = ?");
        }
        if (stateCount > 0) {
            sql.append(" AND state IN (").append(placeholders(stateCount)).append(")");
        }
        if (outcomeCount > 0) {
            sql.append(" AND outcome IN (").append(placeholders(outcomeCount)).append(")");
        }
        if (reasonCount > 0) {
            sql.append(" AND failure_reason IN (").append(placeholders(reasonCount)).append(")");
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
