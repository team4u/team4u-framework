package com.team4u.framework.lease.jdbc.dialect;

/**
 * 租赁数据库方言接口
 * <p>
 * 用于处理不同数据库（如 MySQL, PostgreSQL）在 SQL 语法上的差异，特别是针对分页和抢占逻辑的实现。
 *
 * @author jay.wu
 */
public interface LeaseDbDialect {

    /**
     * 构建查找可抢占任务候选者的 SQL。
     * <p>
     * 该方法负责生成一个完整的 `SELECT` 语句，该语句应能通过联合查询或复杂 OR 条件
     * 同时覆盖待命任务（READY）和已过期任务（RUNNING 且租约失效）。
     *
     * @param tableName  任务表名
     * @param columns    返回的列字段
     * @param queueCount 订阅的队列数量
     * @return 完整的 SELECT SQL 语句
     */
    String buildAcquireCandidateSql(String tableName, String columns, int queueCount);

    /**
     * 构建分页查询的排序和分页 SQL 后缀
     * <p>
     * WHERE 条件由 DAO 统一构建，方言只负责提供排序规则和分页语法。
     *
     * @return `ORDER BY / LIMIT / OFFSET` 片段
     */
    String buildQuerySuffix();
}
