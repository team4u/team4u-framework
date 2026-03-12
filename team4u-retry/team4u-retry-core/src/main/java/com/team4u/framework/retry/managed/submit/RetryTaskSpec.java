package com.team4u.framework.retry.managed.submit;

import com.team4u.framework.retry.api.RecoverySpec;
import com.team4u.framework.retry.api.RetryPolicy;
import lombok.Builder;
import lombok.Getter;

import java.util.concurrent.Callable;

/**
 * 任务规格对象。
 * 用于定义一次需要执行并支持托管重试的任务。
 *
 * @param <T> 任务返回结果类型
 */
@Getter
@Builder
public class RetryTaskSpec<T> {
    /**
     * 业务幂等键，主要用于去重和追踪。
     */
    private final String idempotencyKey;

    /**
     * 前台执行的具体业务逻辑。
     */
    private final Callable<T> executor;

    /**
     * 托管恢复所需的规格定义。
     */
    private final RecoverySpec recovery;

    /**
     * 该任务遵循的重试策略。
     */
    private final RetryPolicy policy;
}
