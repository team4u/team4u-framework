package com.team4u.framework.ratelimiter.proxy;

/**
 * 注解限流的拒绝策略
 *
 * @author jay.wu
 */
public enum RateLimitReject {

    /**
     * 抛出 {@code RateLimitException}（携带裁决结果）
     */
    EXCEPTION,

    /**
     * 返回空值：对象类型返回 null，基本类型返回默认值（0/false 等），
     * void 方法直接拦截不执行目标方法
     */
    NULL_VALUE
}
