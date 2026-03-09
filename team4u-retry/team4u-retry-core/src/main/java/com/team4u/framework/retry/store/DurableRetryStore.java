package com.team4u.framework.retry.store;

import com.team4u.framework.retry.store.record.*;

import java.time.Instant;
import java.util.Optional;

/**
 * 持久化能力接口契约。
 * 定义了管理重试任务状态机流转的标准存储方法。
 */
public interface DurableRetryStore {

    /**
     * 持久化保存初始意图并返回任务句柄
     */
    TaskHandle create(RetryRecord initialRecord);

    /**
     * 标记任务正在执行中
     */
    void markRunning(String taskId, AttemptRecord attempt);

    /**
     * 标记任务进入下一次排队调度
     */
    void scheduleNext(
            String taskId,
            AttemptRecord attempt,
            Instant nextRunAt,
            FailureRecord failure);

    /**
     * 标记任务最终成功
     */
    void markSucceeded(String taskId, SuccessRecord success);

    /**
     * 标记任务由于耗尽重试次数或遇到无法重试的异常而引发的最终终态失败
     */
    void markFailed(String taskId, FailureRecord failure);

    /**
     * 显式取消任务
     */
    void cancel(String taskId, CancelRecord cancel);

    /**
     * 查询快照记录
     */
    Optional<RetryRecord> get(String taskId);
}
