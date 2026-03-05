package com.team4u.framework.retry.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 启用自动重试代理功能。
 * <p>
 * 标注此注解后，系统将自动扫描 Spring 容器中带有
 * {@link com.team4u.framework.retry.proxy.Retryable} 注解的 Bean，
 * 并通过 team4u-proxy 自动为其织入重试能力。
 *
 * @author jay.wu
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({RetrySpringConfiguration.class, RetryLifecycleConfiguration.class})
public @interface EnableRetry {
}
