package com.team4u.framework.retry.store.record;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 重试状态流转详情，封装了任务从一次尝试失败后迁移到“等待重试”状态时的变化信息。
 */
@Data
@Builder
public class RetryTransition {

    /**
     * 更新后的已尝试次数
     */
    private int attempts;

    /**
     * 计算出的下一次实际运行时间
     */
    private Instant nextRunAt;

    /**
     * 最近一次执行失败对应的错误码
     */
    private String lastErrorCode;

    /**
     * 最近一次执行失败对应的错误消息摘要
     */
    private String lastErrorMessage;

    /**
     * 关联的后台调度任务 ID（若已分派）
     */
    private String backendTaskId;
}
