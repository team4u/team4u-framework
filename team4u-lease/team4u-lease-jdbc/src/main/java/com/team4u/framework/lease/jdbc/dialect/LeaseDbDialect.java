package com.team4u.framework.lease.jdbc.dialect;

/**
 * SQL dialect for the five-state lease task table.
 */
public interface LeaseDbDialect {

    String buildAcquireCandidateSql(String tableName, String columns, int taskTypeCount);

    String buildQuerySuffix();
}
