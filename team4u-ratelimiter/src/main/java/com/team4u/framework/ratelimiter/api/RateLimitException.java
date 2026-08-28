package com.team4u.framework.ratelimiter.api;

import lombok.Getter;

/**
 * 限流拒绝异常
 * <p>
 * 由静态门面 {@code RateLimiters.acquire} 与注解代理（{@code RateLimitReject#EXCEPTION}）
 * 在请求被拒绝时抛出，携带完整裁决结果供调用方提取。
 * </p>
 *
 * @author jay.wu
 */
@Getter
public class RateLimitException extends RuntimeException {

    /**
     * 裁决结果（含检查点、规则、剩余额度、建议重试等待等）
     */
    private final RateLimitResult result;

    public RateLimitException(RateLimitResult result) {
        super("Rate limited|point=" + result.getPoint()
                + "|ruleId=" + result.getRuleId()
                + "|reason=" + result.getReason()
                + "|retryAfterMillis=" + result.getRetryAfterMillis());
        this.result = result;
    }
}
