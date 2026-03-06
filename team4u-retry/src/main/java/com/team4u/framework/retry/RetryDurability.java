package com.team4u.framework.retry;

/**
 * 重试持久化级别
 */
public enum RetryDurability {
    /**
     * 级别1：纯内存重试。
     * 极速，但不防宕机。
     */
    MEMORY_ONLY,

    /**
     * 级别2：内存优先，耗尽后持久化。
     * 防由于下游持续故障导致的内存堆积，但存在宕机丢失风险。
     */
    MEMORY_FALLBACK,

    /**
     * 级别3：持久化 + 至少一次（防丢失）。
     * 执行前先登记意图，成功后销毁，确保绝不丢失。
     * 警告：这是“防丢失”的 durability，不是 exactly-once，更不是分布式事务意义上的强一致性。
     * 若在业务执行成功后、清理意图前进程崩溃，后台恢复时会发生重复执行。
     * 因此，业务方必须保证任务的幂等性。
     */
    AT_LEAST_ONCE_DURABLE
}
