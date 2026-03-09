package com.team4u.framework.retry;

import lombok.Data;

/**
 * 重试快照构建上下文
 * <p>
 * 承载在构建任务快照时的阶段性信息。
 */
@Data
public final class RetryPayloadContext {

    /**
     * 当前构建阶段
     */
    private final Phase phase;
    /**
     * 已执行的尝试次数
     */
    private final int executedAttempts;

    /**
     * 创建预登记意图阶段的上下文
     */
    public static RetryPayloadContext prepareIntent() {
        return new RetryPayloadContext(Phase.PREPARE_INTENT, 0);
    }

    /**
     * 创建转入后端存储阶段的上下文
     *
     * @param executedAttempts 已执行的尝试次数
     */
    public static RetryPayloadContext handoffToBackend(int executedAttempts) {
        if (executedAttempts < 1) {
            throw new IllegalArgumentException("executedAttempts must be >= 1 when handing off to backend");
        }
        return new RetryPayloadContext(Phase.HANDOFF_TO_BACKEND, executedAttempts);
    }

    /**
     * 快照构建阶段枚举
     */
    public enum Phase {
        /**
         * 预准备意向阶段
         * <p>
         * 在任务首次执行前，将任务元数据预先登记到后端，此时尚未正式开始重试。
         */
        PREPARE_INTENT,
        /**
         * 转交给后端阶段
         * <p>
         * 内存重试配额已耗尽，任务元数据正式移交给后端队列以进行后续的恢复与重试。
         */
        HANDOFF_TO_BACKEND
    }
}