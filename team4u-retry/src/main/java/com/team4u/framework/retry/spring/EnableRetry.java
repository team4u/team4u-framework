package com.team4u.framework.retry.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 启用重试功能
 * <p>
 * 在 Spring 配置类上添加此注解，即可自动开启基于注解的重试能力。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(RetrySpringConfiguration.class)
public @interface EnableRetry {
}
