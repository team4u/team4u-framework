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
     * 将任务正式移交给后端重试
     * <p>
     * 激活预处理的意向，使其进入重试队列或调度系统。
     *
     * @param snapshot 任务快照
     */
    void prepare(RetryTaskSnapshot snapshot);

    /**
     * 正式移交任务至异步处理链（如进入延迟队列或租约系统）
     *
     * @param taskId      任务 ID
     * @param delayMillis 延迟触发毫秒数
     */
    void handoff(String taskId, long delayMillis);

    /**
     * 完成重试任务
     * <p>
     * 当任务在内存中最终执行成功，或者重试流程终止时，清理后端的任务状态。
     */
    void complete(String taskId);
}
