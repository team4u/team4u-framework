package com.team4u.framework.retry.backend;

/**
 * 重试后端
 * <p>
 * 定义重试任务持久化存储与状态管理的抽象接口
 *
 * @author jay.wu
 */
public interface RetryBackend {

    /**
     * 预处理重试意向或保存初始进度
     * <p>
     * 当任务首次尝试失败且符合持久化条件时，调用此方法以确保后端存储已初始化该任务 ID；
     * 或者用于在任务正式移交前同步当前执行状态。
     *
     * @param snapshot 任务快照
     */
    void prepare(RetryTaskSnapshot snapshot);

    /**
     * 正式将任务移交给异步重试后端（如进入延迟队列或调度系统）
     *
     * @param taskId      已持久化的任务 ID
     * @param delayMillis 下次重试的延迟触发时间（毫秒）
     */
    void handoff(String taskId, long delayMillis);

    /**
     * 保存当前任务执行的最新进度
     * <p>
     * 记录已执行次数、最后一次异常信息等状态，用于在节点宕机或重启后能够恢复执行。
     *
     * @param snapshot 包含最新状态的任务快照
     */
    void saveProgress(RetryTaskSnapshot snapshot);

    /**
     * 彻底结束重试任务
     * <p>
     * 当任务最终成功、最终失败或被取消时调用，后端根据请求状态清理或标记该任务。
     *
     * @param taskId  任务 ID
     * @param request 关闭请求信息
     */
    void close(String taskId, RetryCloseRequest request);
}
