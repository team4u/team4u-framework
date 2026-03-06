package com.team4u.framework.retry;

/**
 * 重试快照构建上下文
 * <p>
 * 承载在构建任务快照时的阶段性信息。
 */
public final class RetryPayloadContext {

    private final Phase phase;
    private final int executedAttempts;

    private RetryPayloadContext(Phase phase, int executedAttempts) {
        this.phase = phase;
        this.executedAttempts = executedAttempts;
    }

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
     * 获取当前构建阶段
     */
    public Phase getPhase() {
        return phase;
    }

    /**
     * 获取已执行的尝试次数
     */
    public int getExecutedAttempts() {
        return executedAttempts;
    }

    /**
     * 快照构建阶段枚举
     */
    public enum Phase {
        /**
         * 预登记意图阶段
         */
        PREPARE_INTENT,
        /**
         * 转入后端存储阶段
         */
        HANDOFF_TO_BACKEND
    }
}
