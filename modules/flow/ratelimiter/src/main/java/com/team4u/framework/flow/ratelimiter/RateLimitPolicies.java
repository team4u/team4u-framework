package com.team4u.framework.flow.ratelimiter;

import com.team4u.framework.ratelimiter.core.RateLimitEngine;

import java.util.function.Function;

/**
 * 流程限流策略工厂便捷工具类
 *
 * @author jay.wu
 */
public final class RateLimitPolicies {

    private RateLimitPolicies() { }

    /**
     * 创建默认限流策略（FAIL 模式，1 个许可）
     */
    public static <K> RateLimitPolicy<K> of(String point) {
        return RateLimitPolicy.of(point);
    }

    /**
     * 创建默认限流策略并指定上下文提取函数
     */
    public static <K> RateLimitPolicy<K> of(String point, Function<K, ?> contextExtractor) {
        return RateLimitPolicy.of(point, contextExtractor);
    }

    /**
     * 创建故障模式限流策略（Gate.fail，触发重试）
     */
    public static <K> RateLimitPolicy<K> fail(String point) {
        return RateLimitPolicy.fail(point);
    }

    /**
     * 创建故障模式限流策略并指定上下文提取函数
     */
    public static <K> RateLimitPolicy<K> fail(String point, Function<K, ?> contextExtractor) {
        return RateLimitPolicy.fail(point, contextExtractor);
    }

    /**
     * 创建业务拒绝模式限流策略（Gate.reject，直接短路降级）
     */
    public static <K> RateLimitPolicy<K> reject(String point) {
        return RateLimitPolicy.reject(point);
    }

    /**
     * 创建业务拒绝模式限流策略并指定上下文提取函数
     */
    public static <K> RateLimitPolicy<K> reject(String point, Function<K, ?> contextExtractor) {
        return RateLimitPolicy.reject(point, contextExtractor);
    }

    /**
     * 基于显式引擎创建限流策略
     */
    public static <K> RateLimitPolicy<K> of(RateLimitEngine engine, String point) {
        return RateLimitPolicy.<K>builder().engine(engine).point(point).build();
    }

    /**
     * 基于显式引擎创建限流策略并指定上下文提取函数
     */
    public static <K> RateLimitPolicy<K> of(RateLimitEngine engine, String point, Function<K, ?> contextExtractor) {
        return RateLimitPolicy.<K>builder()
                .engine(engine)
                .point(point)
                .contextExtractor(contextExtractor)
                .build();
    }
}
