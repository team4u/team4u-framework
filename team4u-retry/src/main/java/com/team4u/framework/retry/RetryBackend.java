package com.team4u.framework.retry;

/**
 * 重试后端存储接口
 * <p>
 * 定义重试任务在持久化介质中的存储与状态管理行为。
 */
public interface RetryBackend {

    /**
     * 保存重试意图
     * <p>
     * 在执行重试逻辑前持久化任务快照，用于系统崩溃后的恢复。
     *
     * @param taskType 任务类型
     * @param payload  任务快照数据
     * @return 意图标识 ID
     */
    String saveIntent(String taskType, String payload);

    /**
     * 完成重试意图
     * <p>
     * 任务成功执行后执行清理操作。
     *
     * @param intentId 意图标识 ID
     */
    void completeIntent(String intentId);

    /**
     * 标记重试意图为最终失败
     * <p>
     * 记录因超过最大重试次数或触发不可重试异常而彻底放弃的任务状态。
     *
     * @param intentId 意图标识 ID
     * @param cause    导致失败的原因
     */
    void markTerminalFailure(String intentId, Throwable cause);

    /**
     * 将任务转入延迟重试队列
     * <p>
     * 适用于需要等待特定退避时间后再执行的重试任务。
     *
     * @param intentId 意图标识 ID
     * @param taskType 任务类型
     * @param payload  任务快照数据
     * @param delay    延迟等待时间（毫秒）
     */
    void submitForDelay(String intentId, String taskType, String payload, long delay);
}
