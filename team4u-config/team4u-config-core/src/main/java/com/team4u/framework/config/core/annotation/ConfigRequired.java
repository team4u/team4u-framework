package com.team4u.framework.config.core.annotation;

import java.lang.annotation.*;

/**
 * 必填配置注解
 * <p>
 * 将配置项标记为必填。
 * 如果配置源中未找到对应值，且没有通过 {@link ConfigDefault} 提供默认值，框架在运行时将抛出异常。
 * </p>
 *
 * @author jay.wu
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigRequired {
}
