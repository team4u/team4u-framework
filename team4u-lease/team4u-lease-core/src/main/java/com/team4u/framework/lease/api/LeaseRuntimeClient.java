package com.team4u.framework.lease.api;

import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.model.*;
import com.team4u.framework.lease.runtime.LeaseWorker;

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
     * 关闭当前租约任务。
     *
     * @param handle  租约操作句柄
     * @param request 关闭请求
     * @return 非空的关闭结果
     */
    LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request);

    /**
     * 续约（心跳机制）
     * <p>
     * 对于执行耗时较长的任务，通过该方法延长租约的过期时间，防止其被其他节点误判为超时并重新抢占。
     *
     * @param handle       租约操作句柄
     * @param extendMillis 期望延长的毫秒数
     * @return 非空的操作结果状态
     */
    LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis);

    /**
     * 显式释放租约进入延迟状态
     * <p>
     * 释放操作将当前任务交还给调度系统，并指定其在未来某个时刻再次拉起。
     *
     * @param handle  租约操作句柄
     * @param request 释放请求详情
     * @return 非空的操作结果状态
     */
    LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request);
}
