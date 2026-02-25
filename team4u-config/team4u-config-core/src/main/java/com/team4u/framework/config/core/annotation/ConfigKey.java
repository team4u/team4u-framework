package com.team4u.framework.config.core.annotation;

import java.lang.annotation.*;

/**
 * 配置键注解
 * <p>
 * 用于明确指定该方法对应的配置 Key，跳过自动推断逻辑。
 * 支持绝对路径（以点号开头，忽略类前缀）和相对路径。
 *
 * @author jay.wu
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigKey {

    /**
     * 配置键名
     *
     * @return 配置键名
     */
    String value();
}
