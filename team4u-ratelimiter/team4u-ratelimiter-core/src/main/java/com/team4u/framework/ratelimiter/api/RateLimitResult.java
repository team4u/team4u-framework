package com.team4u.framework.ratelimiter.api;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 一次限流检查的裁决结果（不可变）
 * <p>
 * 由 {@code RateLimitEngine} 与各算法产生。多规则场景下：
 * 任一规则拒绝立即返回（首拒即停，{@code ruleId} 为拒绝规则）；
 * 全部通过时返回最后一条通过规则的结果。
 * {@code remaining} 与 {@code retryAfterMillis} 由算法尽力提供，无法精确计算时为 {@code null}。
 * </p>
 *
 * @author jay.wu
 */
@Getter
@Builder(toBuilder = true)
@ToString
public class RateLimitResult {

    /**
     * 是否放行
     */
    private final boolean allowed;

    /**
     * 限流检查点标识
     */
    private final String point;

    /**
     * 裁决规则标识：拒绝时为触发拒绝的规则；全部通过时为最后一条通过的规则；无规则时为 {@code null}
     */
    private final String ruleId;

    /**
     * 剩余额度（窗口内还能通过的请求数）；无法精确计算时为 {@code null}
     */
    private final Long remaining;

    /**
     * 建议重试等待毫秒数；无意义（如固定浮窗）或无需等待时为 {@code null}
     */
    private final Long retryAfterMillis;

    /**
     * 裁决时刻（epoch 毫秒）；历史窗口场景供客户端回填记录
     */
    private final long decisionTimeMillis;

    /**
     * 裁决原因
     */
    private final RateLimitReason reason;

    /**
     * 无规则放行结果
     *
     * @param point 检查点标识
     * @param now   裁决时刻（epoch 毫秒）
     */
    public static RateLimitResult allowedNoRule(String point, long now) {
        return RateLimitResult.builder()
                .allowed(true)
                .point(point)
                .decisionTimeMillis(now)
                .reason(RateLimitReason.NO_RULE)
                .build();
    }
}
