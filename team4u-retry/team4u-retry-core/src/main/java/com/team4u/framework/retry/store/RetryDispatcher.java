package com.team4u.framework.retry.store;

import com.team4u.framework.retry.store.record.DispatchResult;
import com.team4u.framework.retry.store.record.RetryDispatchCommand;

/**
 * 重试分发器接口，负责将前台尝试失败的任务移交给后台调度系统（Durable Handoff）。
 * <p>
 * {@link #dispatch(RetryDispatchCommand)} 成功返回即表示任务已被 durable 地推进到
 * {@code WAITING_RETRY} 并完成后台调度；失败则表示 handoff 未完成。
 */
public interface RetryDispatcher {

    /**
     * 分派重试命令至后台调度器。
     *
     * @param command 重试分派命令，包含待运行的任务记录、状态流转及退避延迟
     * @return 分派结果，包含后台任务 ID 及计算出的实际下次运行时间
     */
    DispatchResult dispatch(RetryDispatchCommand command);
}
