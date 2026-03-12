package com.team4u.framework.retry.managed.dispatch;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 分派结果详情，描述了任务被成功移交给后台分发器的结果。
 */
@Data
@Builder
public class DispatchResult {

    /**
     * 内部重试系统分配的任务唯一标识
     */
    private String taskId;

    /**
     * 后台调度器（如延迟消息队列或定时调度引擎）返回的任务标识
     */
    private String backendTaskId;

    /**
     * 经由重试策略计算后的下一次实际执行时间戳
     */
    private Instant nextRunAt;
}
