package com.team4u.framework.retry.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 启用自动重试功能
 * <p>
 * 在 Spring 配置类上添加此注解，即可通过 AOP 机制自动开启对
 * {@link com.team4u.framework.retry.proxy.Retryable} 注解的支持。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({RetryAutoProxyRegistrar.class, RetrySpringConfiguration.class, RetryLifecycleConfiguration.class})
public @interface EnableRetry {
}
