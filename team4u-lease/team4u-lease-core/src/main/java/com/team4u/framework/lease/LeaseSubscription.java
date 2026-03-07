package com.team4u.framework.lease;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 租约订阅声明
 * <p>
 * 定义了工作者关注的具体任务队列。
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseSubscription {

    /**
     * 订阅的队列名称
     */
    private final String queue;
}
