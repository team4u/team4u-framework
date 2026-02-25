package com.team4u.framework.config.core.annotation;

import java.lang.annotation.*;

/**
 * 配置前缀注解
 * <p>
 * 用于在接口类级别定义统一的配置前缀。
 * 注意：如果在创建代理时也显式指定了前缀，则该注解定义的值会追加在显式前缀之后（叠加合并）。
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
