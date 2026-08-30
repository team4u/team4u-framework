package com.team4u.framework.ratelimiter.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 启用注解限流
 * <p>
 * 在 Spring 配置类上添加此注解，容器中含 {@code @RateLimit} 方法的 Bean
 * 将被自动包装为限流代理（类中方法被拦截时经 {@code RateLimiters} 全局门面裁决）。
 * </p>
 *
 * @author jay.wu
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(RateLimitSpringConfiguration.class)
public @interface EnableRateLimit {
}
