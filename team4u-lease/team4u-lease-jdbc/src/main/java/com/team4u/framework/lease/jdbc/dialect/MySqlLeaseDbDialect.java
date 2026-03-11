package com.team4u.framework.lease.jdbc.dialect;

import com.team4u.framework.lease.jdbc.SqlExpression;

/**
 * MySQL 的租赁数据库方言实现
 *
 * @author jay.wu
 */
public class MySqlLeaseDbDialect implements LeaseDbDialect {

    @Override
    public String buildAcquireCandidateSql(int queueCount) {
        return "WHERE queue_name IN (" + SqlExpression.placeholders(queueCount) + ") "
                + "AND ((state = 'READY' AND visible_at <= ?) OR (state = 'RUNNING' AND lease_expires_at <= ?)) "
                + "ORDER BY priority DESC, created_at ASC, task_id ASC "
                + "LIMIT ?";
    }

    @Override
    public String buildQuerySuffix() {
        return " ORDER BY created_at ASC, task_id ASC LIMIT ? OFFSET ?";
    }
}

