package com.team4u.framework.retry.backend;

/**
 * 重试后端
 * <p>
 * 定义重试任务持久化存储与状态管理的抽象接口。
 */
public interface RetryBackend {

    /**
     * 预处理重试意向（Intent）
     * <p>
     * 在内存重试失败或直接进入异步重试前，将任务信息预存在后端。
     * 此时任务处于“挂起”状态，直到被显式触发（handoff）或完成（complete）。
     *
     * @param taskType 任务类型标识
     * @param payload  序列化后的任务载荷
     * @return 预处理后的意向 ID
     */
    String prepare(String taskType, String payload);

    /**
     * 将任务正式移交给后端重试
     * <p>
     * 激活预处理的意向，使其进入重试队列或调度系统。
     *
     * @param intentId    意向 ID
     * @param delayMillis 触发重试前的延迟毫秒数
     */
    void handoff(String intentId, long delayMillis);

    /**
     * 完成重试任务
     * <p>
     * 当任务在内存中最终执行成功，或者重试流程提前终止时，清理后端的任务状态。
     *
     * @param intentId 意向 ID
     */
    void complete(String intentId);
}
