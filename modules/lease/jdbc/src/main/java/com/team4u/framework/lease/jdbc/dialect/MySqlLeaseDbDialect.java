package com.team4u.framework.lease.jdbc.dialect;

import com.team4u.framework.base.jdbc.SqlExpression;

public class MySqlLeaseDbDialect implements LeaseDbDialect {

    @Override
    public String buildAcquireCandidateSql(String tableName, String columns, int taskTypeCount) {
        String taskTypes = SqlExpression.placeholders(taskTypeCount);
        return "SELECT " + columns + " FROM ("
                + "SELECT " + columns + " FROM " + tableName + " "
                + "WHERE queue_name = ? AND task_type IN (" + taskTypes + ") "
                + "AND status = ? AND visible_at <= ? "
                + "UNION ALL "
                + "SELECT " + columns + " FROM " + tableName + " "
                + "WHERE queue_name = ? AND task_type IN (" + taskTypes + ") "
                + "AND status = ? AND lease_expires_at <= ?"
                + ") candidates "
                + "ORDER BY priority DESC, created_at ASC, task_id ASC LIMIT ?";
    }

    @Override
    public String buildQuerySuffix() {
        return " ORDER BY created_at ASC, task_id ASC LIMIT ? OFFSET ?";
    }
}
