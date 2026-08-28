package com.team4u.framework.ratelimiter.api;

import lombok.Getter;

/**
 * 限流拒绝异常
 * <p>
 * 由注解代理（{@code RateLimitReject#EXCEPTION}）在方法调用被拒绝时抛出——被代理
 * 方法的签名携带不了裁决结果，异常是穿越该边界的传输手段。编程式路径
 * （{@code RateLimiters.acquire} / 引擎）拒绝不抛异常、返回完整裁决结果，
 * 由调用方自行决定是否抛出本异常（构造开放，可携带裁决结果复用）。
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
