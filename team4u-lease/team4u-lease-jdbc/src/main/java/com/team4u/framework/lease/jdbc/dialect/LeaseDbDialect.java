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
     * 构建查找可抢占任务候选者的 SQL 片段
     *
     * @param queueCount 订阅的队列数量
     * @return `WHERE / ORDER BY / LIMIT` 片段
     */
    String buildAcquireCandidateSql(int queueCount);

    /**
     * 构建分页查询的排序和分页 SQL 后缀
     * <p>
     * WHERE 条件由 DAO 统一构建，方言只负责提供排序规则和分页语法。
     *
     * @return `ORDER BY / LIMIT / OFFSET` 片段
     */
    String buildQuerySuffix();
}
