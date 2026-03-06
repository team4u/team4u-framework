package com.team4u.framework.lease;

/**
 * 租约后端接口，定义了任务的完整生命周期管理。
 * <p>
 * 后端实现负责任务的持久化存储、租约发放以及状态流转。
 */
public interface LeaseBackend extends LeasePublisher {

    /**
     * 获取一条待处理的任务。
     * <p>
     * 在分布式环境下，通过该方法竞争任务所有权（租约）。
     * 若当前无可用任务，该方法会进入等待状态直至超时。
     *
     * @param workerId         当前请求的 Worker 身份标识
     * @param leaseMillis      租约时长（毫秒），Worker 需在此时间内完成任务或续约
     * @param waitTimeoutMillis 阻塞获取的最大等待时长（毫秒）
     * @return 成功获取到的任务准入证（LeaseGrant）；若超时未获取到则返回 null
     * @throws InterruptedException 获取过程中若线程被中断抛出
     */
    LeaseGrant acquire(String workerId, long leaseMillis, long waitTimeoutMillis) throws InterruptedException;

    /**
     * 确认任务已成功完成，并清除对应的任务记录或租约。
     *
     * @param taskId     任务唯一 ID
     * @param workerId   当前持有租约的 Worker ID
     * @param leaseToken 租约令牌，用于一致性校验
     */
    void ack(String taskId, String workerId, String leaseToken);

    /**
     * 标记任务执行失败，并安排在指定延迟后重新尝试。
     *
     * @param taskId      任务唯一 ID
     * @param workerId    当前持有租约的 Worker ID
     * @param leaseToken  租约令牌
     * @param delayMillis 下次可见的延迟时间（毫秒）
     * @param cause       导致重试的异常原因
     */
    void retry(String taskId, String workerId, String leaseToken, long delayMillis, Throwable cause);

    /**
     * 将任务标记为终态失败（Dead），通常发生在达到最大重试次数后。
     *
     * @param taskId     任务唯一 ID
     * @param workerId   当前持有租约的 Worker ID
     * @param leaseToken 租约令牌
     * @param cause      失败原因
     */
    void fail(String taskId, String workerId, String leaseToken, Throwable cause);

    /**
     * 为租约续期（心跳机制），以防止长耗时任务在执行中被后端判定为锁超时。
     *
     * @param taskId       任务唯一 ID
     * @param workerId     当前持有租约的 Worker ID
     * @param leaseToken   租约令牌
     * @param extendMillis 期望延长的时长（毫秒）
     */
    void heartbeat(String taskId, String workerId, String leaseToken, long extendMillis);
}
