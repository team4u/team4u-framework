package com.team4u.framework.lease;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 表示任务已被某个 worker 成功持有租约。
 */
@Getter
@Builder
@AllArgsConstructor
public class LeaseGrant {

    /**
     * 任务的全局唯一标识
     */
    private final String taskId;
    /**
     * 任务类型，用于路由到特定的处理器
     */
    private final String taskType;
    /**
     * 任务的业务负载数据
     */
    private final String payload;
    /**
     * 当前持有该任务租约的 Worker 标识
     */
    private final String workerId;
    /**
     * 本次租约的唯一通行令牌，用于后续的 ACK、Retry、Heartbeat 校验
     */
    private final String leaseToken;
    /**
     * 该任务已累计尝试执行的次数（包含本次）
     */
    private final int attemptCount;
    /**
     * 任务在后端系统创建的毫秒时间戳
     */
    private final long createdAtMillis;
    /**
     * 任务本次对 Worker 可见（可被竞争）的毫秒时间戳
     */
    private final long visibleAtMillis;
    /**
     * 当前租约的到期毫秒时间戳。超过此时间未续约，任务可能被其他 Worker 抢占。
     */
    private final long leaseExpiresAtMillis;
}
