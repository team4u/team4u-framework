package com.team4u.framework.retry;

/**
 * 重试持久化级别
 */
public enum RetryDurability {
    /**
     * 仅内存重试。
     * 提供极高的性能，但在宕机或系统重启时会丢失重试任务。
     */
    MEMORY_ONLY,

    /**
     * 内存优先，耗尽后降级至持久化存储。
     * 防止由于下游持续故障导致内存堆积，但存在宕机丢失未降级任务的风险。
     */
    MEMORY_FALLBACK,

    /**
     * 持久化 + 至少一次投递保证。
     * 执行前预登记意图，成功后清理，确保任务在系统崩溃后可恢复。
     * 业务方必须保证任务执行的幂等性。
     */
    AT_LEAST_ONCE_DURABLE
}
