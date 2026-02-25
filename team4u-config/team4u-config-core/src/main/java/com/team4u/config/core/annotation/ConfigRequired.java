package com.team4u.config.core.annotation;

import java.lang.annotation.*;

/**
 * 必填配置注解
 * <p>
 * 标记配置项为必填。如果未找到配置值且没有默认值，将抛出异常。
 *
 * @author fjay
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigRequired {
}
