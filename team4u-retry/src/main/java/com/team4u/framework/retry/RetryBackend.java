package com.team4u.framework.retry;

/**
 * 重试后端存储接口
 */
public interface RetryBackend {

    /**
     * 保存重试意图（预写日志）
     *
     * @param taskType 任务类型
     * @param payload  任务快照数据
     * @return 意图标识 ID
     */
    String saveIntent(String taskType, String payload);

    /**
     * 完成重试意图（清理日志）
     *
     * @param intentId 意图标识 ID
     */
    void completeIntent(String intentId);

    /**
     * 标记重试意图为终态失败
     * (因不可重试异常或达到最大重试次数导致彻底放弃，不再恢复)
     *
     * @param intentId 意图标识 ID
     * @param cause    导致终态失败的原因
     */
    void markTerminalFailure(String intentId, Throwable cause);

    /**
     * 将任务转入延迟重试队列
     *
     * @param intentId 意图标识 ID
     * @param taskType 任务类型
     * @param payload  任务快照数据
     * @param delay    延迟时间（毫秒）
     */
    void submitForDelay(String intentId, String taskType, String payload, long delay);
}
