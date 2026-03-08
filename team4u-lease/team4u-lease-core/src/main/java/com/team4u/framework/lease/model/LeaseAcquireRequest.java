package com.team4u.framework.lease.model;

import lombok.*;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 租约抢占请求模型
 * <p>
 * 封装了消费者（Worker）向后端请求锁定任务的具体参数。
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseAcquireRequest {

    /**
     * 发起抢占请求的工作者唯一标识
     */
    private final String workerId;
    /**
     * 期望获取的租约有效期毫秒数（若超时未续约或 Ack，租约将失效）
     */
    private final long leaseMillis;
    /**
     * 在无任务可用时，允许阻塞等待的最长时间毫秒数
     */
    private final long waitTimeoutMillis;
    /**
     * 当前工作者订阅的队列列表
     */
    @Singular
    private final Set<LeaseSubscription> subscriptions;

    public Set<LeaseSubscription> getSubscriptions() {
        if (subscriptions == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<LeaseSubscription>(subscriptions));
    }
}
