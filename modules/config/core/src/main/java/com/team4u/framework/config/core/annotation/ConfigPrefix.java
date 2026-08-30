package com.team4u.framework.config.core.annotation;

import java.lang.annotation.*;

/**
 * 配置前缀注解
 * <p>
 * 用于在接口类级别定义统一的配置键前缀。
 * 该接口下所有未声明绝对路径的方法都将继承此前缀。
 * 注意：如果在创建代理实例时显式指定了初始前缀，则此注解定义的值会与初始前缀按级联方式合并。
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigPrefix {
    /**
     * 配置前缀，例如 "app.datasource"
     *
     * @return 统一定义的配置前缀
     */
    String value();
}
