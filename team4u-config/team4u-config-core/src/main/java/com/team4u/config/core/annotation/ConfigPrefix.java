package com.team4u.config.core.annotation;

import java.lang.annotation.*;

/**
 * 配置前缀注解
 * 用于在接口类级别定义统一的配置前缀
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE) // 作用于接口/类
public @interface ConfigPrefix {
    /**
     * 配置前缀，例如 "app.datasource"
     */
    String value();
}
