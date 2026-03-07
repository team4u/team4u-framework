package com.team4u.framework.lease;

/**
 * 任务发布器接口。
 * <p>
 * 定义了将业务任务提交至租约系统的标准操作，支持立即发布、延迟发布以及对存量任务的状态调整。
 */
public interface LeasePublisher {

    /**
     * 发布一条立即生效的任务。
     *
     * @param taskType 任务类型，用于匹配对应的 {@link LeaseTaskHandler}
     * @param payload  业务负载数据，通常为 JSON 格式的字符串
     * @return 任务唯一标识（TaskId）
     */
    String publish(String taskType, String payload);

    /**
     * 发布一条具有延迟可见性的任务。
     *
     * @param taskType    任务类型
     * @param payload     业务负载数据
     * @param delayMillis 延迟到期毫秒数。在此时间段内，任务对 Worker 不见。
     * @return 任务唯一标识（TaskId）
     */
    String publish(String taskType, String payload, long delayMillis);

    /**
     * 重新编排待处理任务的可见时间。
     * <p>
     * 通常用于手动干预任务执行顺序或人工触发重试。如果任务已处于终态，则该操作无效。
     *
     * @param taskId      任务唯一 ID
     * @param delayMillis 从当前时间开始计算的延迟毫秒数
     */
    void reschedule(String taskId, long delayMillis);

    /**
     * 强制取消一个任务。
     * <p>
     * 若任务尚未被消费，则直接标记为 DEAD；若任务正在执行中，该操作仅改变存储层状态，
     * 不会强制中断物理线程（需配合业务幂等或心跳校验）。
     *
     * @param taskId 任务唯一 ID
     */
    void cancel(String taskId);
}
