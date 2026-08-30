package com.team4u.framework.ratelimiter.proxy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级限流注解
 * <p>
 * 标注于需要限流的方法上，经代理拦截后按 {@link #point()} 路由到限流引擎裁决。
 * 方法参数（按参数名）自动组装为检查上下文，供规则键模板
 * （如 {@code key = "${orderId}"}）渲染按维度计数的键。
 * 要求类编译时保留参数名（项目已默认开启 {@code -parameters}）。
 * </p>
 *
 * @author jay.wu
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流检查点的简写别名（对应配置键 team4u.ratelimiter.{point} 的规则组）
     * <p>
     * 与 {@link #point()} 互为别名：二者至少设置一个；同时设置时必须一致。
     * 仅检查点一项必填时推荐用简写，如 {@code @RateLimit("order-create")}。
     * </p>
     */
    String value() default "";

    /**
     * 限流检查点（对应配置键 team4u.ratelimiter.{point} 的规则组）
     */
    String point() default "";

    /**
     * 本次申请的许可数
     */
    int permits() default 1;

    /**
     * 拒绝策略：抛异常或返回空值
     */
    RateLimitReject reject() default RateLimitReject.EXCEPTION;
}
