package com.team4u.framework.retry.backend;

/**
 * 重试持久化适配器
 * <p>
 * 定义重试任务持久化存储与状态管理的抽象接口，取代旧的 RetryBackend。
 *
 * @author jay.wu
 */
public interface RetryBackend {

    /**
     * 保存重试任务快照
     * <p>
     * 具体实现可以只支持 prepare 阶段的初始化持久化，不保证支持运行中快照更新。
     *
     * @param snapshot 任务快照
     */
    void save(RetryTaskSnapshot snapshot);

    /**
     * 正式移交任务至异步处理链（如进入延迟队列或租约系统）
     *
     * @param taskId      任务 ID
     * @param delayMillis 延迟触发毫秒数
     */
    void handoff(String taskId, long delayMillis);

    /**
     * 删除/完成重试任务
     *
     * @param taskId 任务 ID
     */
    void delete(String taskId);
}
