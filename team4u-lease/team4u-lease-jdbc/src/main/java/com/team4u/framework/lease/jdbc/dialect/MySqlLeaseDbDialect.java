package com.team4u.framework.lease.jdbc.dialect;

import com.team4u.framework.lease.jdbc.SqlExpression;

/**
 * MySQL 的租赁数据库方言实现
 *
 * @author jay.wu
 */
public class MySqlLeaseDbDialect implements LeaseDbDialect {

    @Override
    public String buildAcquireCandidateSql(String tableName, String columns, int queueCount) {
        String readyQueues = SqlExpression.placeholders(queueCount);
        String expiredQueues = SqlExpression.placeholders(queueCount);
        // 使用 UNION ALL 分别针对 READY 和 RUNNING 状态进行查询。
        // 这样可以精确命中数据库中根据 (state, visible_at) 和 (state, lease_expires_at) 分别建立的专用索引，
        // 避免在单个查询中使用复杂的 OR 条件导致索引失效。
        return "SELECT " + columns + " FROM ("
                + "SELECT " + columns + " FROM " + tableName + " "
                + "WHERE task_group IN (" + readyQueues + ") "
                + "AND state = 'READY' AND visible_at <= ? "
                + "UNION ALL "
                + "SELECT " + columns + " FROM " + tableName + " "
                + "WHERE task_group IN (" + expiredQueues + ") "
                + "AND state = 'RUNNING' AND lease_expires_at <= ?"
                + ") acquire_candidates "
                + "ORDER BY priority DESC, created_at ASC, task_id ASC "
                + "LIMIT ?";
    }

    @Override
    public String buildQuerySuffix() {
        return " ORDER BY created_at ASC, task_id ASC LIMIT ? OFFSET ?";
    }
}
