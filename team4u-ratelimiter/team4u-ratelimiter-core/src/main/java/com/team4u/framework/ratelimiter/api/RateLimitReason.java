package com.team4u.framework.ratelimiter.api;

/**
 * 限流裁决原因
 * <p>
 * 描述一次限流检查的最终结论来源，供调用方按需展示与埋点。
 * </p>
 *
 * @author jay.wu
 */
public enum RateLimitReason {

    /**
     * 检查点未配置任何规则，直接放行
     */
    NO_RULE,

    /**
     * 所有规则均通过（多规则时为最后一条通过规则）
     */
    PASS,

    /**
     * 命中阈值被拒绝（首拒即停，裁决规则即触发拒绝的规则）
     */
    THRESHOLD,

    /**
     * 存储故障且规则配置为 failOpen=false（故障关闭），立即拒绝
     */
    STORE_ERROR
}
