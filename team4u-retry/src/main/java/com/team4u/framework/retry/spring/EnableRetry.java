package com.team4u.framework.retry.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({ RetrySpringConfiguration.class, RetryLifecycleConfiguration.class })
public @interface EnableRetry {
}
