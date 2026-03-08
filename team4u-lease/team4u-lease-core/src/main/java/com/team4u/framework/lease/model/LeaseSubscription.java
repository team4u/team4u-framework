package com.team4u.framework.lease.model;

import lombok.*;

/**
 * 租约订阅声明
 * <p>
 * 定义了工作者关注的具体任务队列。
 */
@Data
@Builder
public class LeaseSubscription {

    /**
     * 订阅的队列名称
     */
    private final String queue;
}