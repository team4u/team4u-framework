package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;

/**
 * 租约订阅声明模型
 * <p>
 * 用于标识 Worker 关注的具体任务队列。
 * 在 {@link com.team4u.framework.lease.api.LeaseRuntimeClient#acquire} 抢占任务时，
 * 通过订阅列表声明当前 Worker 可处理的队列范围，后端将只返回这些队列中的任务。
 * <p>
 * 一个 Worker 可以订阅多个队列，实现多队列并发消费。
 */
@Data
@Builder
public class LeaseSubscription {

    /**
     * 订阅的队列名称
     */
    private final String queue;
}