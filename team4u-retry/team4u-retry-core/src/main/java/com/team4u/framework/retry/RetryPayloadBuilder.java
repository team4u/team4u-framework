package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryTaskSnapshot;

/**
 * 重试任务快照构建器
 * <p>
 * 用于在任务进入持久化存储前，构建当前执行上下文的任务快照。
 */
@FunctionalInterface
public interface RetryPayloadBuilder {

    /**
     * 构建任务快照
     *
     * @param context 重试快照构建上下文
     * @return 任务快照数据
     */
    RetryTaskSnapshot build(RetryPayloadContext context);
}
