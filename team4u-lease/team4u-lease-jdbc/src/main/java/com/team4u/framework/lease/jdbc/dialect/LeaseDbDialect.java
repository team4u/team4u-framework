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
     * 构建查找可抢占任务候选者的 SQL
     *
     * @param queueCount 订阅的队列数量
     * @return 完整的 SQL 语句
     */
    String buildAcquireCandidateSql(int queueCount);

    /**
     * 构建分页查询任务的 SQL
     *
     * @param filterQueue    是否过滤队列
     * @param filterTaskType 是否过滤任务类型
     * @param stateCount     过滤的生命周期状态数量
     * @param outcomeCount   过滤的结束结果数量
     * @param reasonCount    过滤的失败原因数量
     * @param filterWorkerId 是否过滤工作节点 ID
     * @return 完整的 SQL 语句
     */
    String buildQuerySql(boolean filterQueue,
                         boolean filterTaskType,
                         int stateCount,
                         int outcomeCount,
                         int reasonCount,
                         boolean filterWorkerId);
}
