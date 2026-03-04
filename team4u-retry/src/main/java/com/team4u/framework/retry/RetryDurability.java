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
     * 级别3：强一致性（预写式日志 WAL）。
     * 执行前先登记意图，成功后销毁，确保绝不丢失。
     */
    STRONG_CONSISTENCY
}
