package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;

/**
 * 租约订阅声明模型
 * <p>
 * 用于标识 Worker 关注的具体任务分组。
 * 在 {@link com.team4u.framework.lease.api.LeaseRuntimeClient#acquire} 抢占任务时，
 * 通过订阅列表声明当前 Worker 可处理的任务分组范围，后端将只返回这些分组中的任务。
 * <p>
 * 一个 Worker 可以订阅多个任务分组，实现多分组并发处理。
 */
@Data
@Builder
public class LeaseTaskGroupSubscription {

    /**
     * 订阅的任务分组名称
     */
    private final String taskGroup;
}
