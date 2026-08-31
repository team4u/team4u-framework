package com.team4u.framework.flow.ratelimiter;

/**
 * 限流超限时的治理决策动作
 *
 * @author jay.wu
 */
public enum RateLimitAction {
    /**
     * 故障决策（Gate.fail）：产生 Failed 状态，可配合重试策略（如 FlowRetryPolicy）退避重试（适用于令牌桶/排队等待）。
     */
    FAIL,

    /**
     * 业务拒绝（Gate.reject）：产生 Rejected 状态，短路退出且绝不触发重试（适用于快速失败/降级）。
     */
    REJECT
}
