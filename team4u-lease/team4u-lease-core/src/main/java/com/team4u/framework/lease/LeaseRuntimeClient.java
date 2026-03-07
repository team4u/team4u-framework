package com.team4u.framework.lease;

/**
 * 运行时租约客户端接口
 * <p>
 * 该接口定义了任务执行过程中的核心生命周期操作。这些操作通常由 {@link LeaseWorker} 调用，
 * 用于管理任务状态流转及租约的时延控制。
 */
public interface LeaseRuntimeClient {

    /**
     * 抢占并锁定一个待处理的任务
     *
     * @param request 抢占请求详情
     * @return 抢占成功的租约授权结果，若无可用任务则返回可能包含空状态的结果
     * @throws InterruptedException 在等待任务过程中线程被中断时抛出
     */
    LeaseGrant acquire(LeaseAcquireRequest request) throws InterruptedException;

    /**
     * 对任务执行结果进行正向确认（Ack）
     * <p>
     * 调用该方法意味着任务已成功处理，后端应将其标记为已完成或删除。
     *
     * @param handle 租约操作句柄
     * @return 确认结果状态
     */
    LeaseRuntimeResult ack(LeaseHandle handle);

    /**
     * 触发任务重试
     * <p>
     * 当任务处理发生可恢复型异常时，标记任务在指定延迟后重新进入就绪队列。
     *
     * @param handle      租约操作句柄
     * @param delayMillis 下次执行的延迟毫秒数
     * @param cause       触发重试的异常原因
     * @return 操作结果状态
     */
    LeaseRuntimeResult retry(LeaseHandle handle, long delayMillis, Throwable cause);

    /**
     * 标记任务执行彻底失败
     * <p>
     * 当任务不可重试或达到重试上限时，将其移动到死信队列或标记为失败终止状态。
     *
     * @param handle 租约操作句柄
     * @param cause  导致失败的异常原因
     * @return 操作结果状态
     */
    LeaseRuntimeResult fail(LeaseHandle handle, Throwable cause);

    /**
     * 续约（心跳机制）
     * <p>
     * 对于执行耗时较长的任务，通过该方法延长租约的过期时间，防止其被其他节点误判为超时并重新抢占。
     *
     * @param handle       租约操作句柄
     * @param extendMillis 期望延长的毫秒数
     * @return 操作结果状态
     */
    LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis);

    /**
     * 显式释放租约进入延迟状态
     * <p>
     * 与重试不同，释放通常用于业务主动让出当前执行权并指定在未来某个时刻再次拉起。
     *
     * @param handle      租约操作句柄
     * @param delayMillis 延迟再次可见的毫秒数
     * @return 操作结果状态
     */
    LeaseRuntimeResult release(LeaseHandle handle, long delayMillis);
}
