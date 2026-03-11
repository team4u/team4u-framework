package com.team4u.framework.retry.domain.store;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 重试状态对象，表示“这个任务现在执行到哪了”。
 */
@Data
@Builder
public class RetryState {
    /**
     * 已执行的总尝试次数（含前台及后台已执行次数之和）
     */
    private int attempts;
    /**
     * 当前任务的生命周期状态
     */
    private RetryStatus status;
    /**
     * 预期的下一次运行时间（用于后台离散退避调度）
     */
    private Instant nextRunAt;
    /**
     * 最后一次处理失败时返回的错误码或异常类名简写
     */
    private String lastErrorCode;
    /**
     * 最后一次处理失败时的详细错误日志或提示信息
     */
    private String lastErrorMessage;
    /**
     * 最终成功完成时间
     */
    private Instant succeededAt;
    /**
     * 最终失败时间
     */
    private Instant failedAt;
    /**
     * 取消时间
     */
    private Instant cancelledAt;
    /**
     * 后台调度引擎返回的关联任务 ID（用于在分布式调度系统中跟踪任务状态）
     */
    private String backendTaskId;
}
