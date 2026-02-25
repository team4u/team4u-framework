package com.team4u.framework.config.core.annotation;

import com.team4u.framework.config.core.convert.PropertyConverter;

import java.lang.annotation.*;

/**
 * 配置转换器注解
 * <p>
 * 用于指定自定义转换逻辑，将原始配置字符串转换为目标类型。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigConverter {

    /**
     * 指定属性转换器类
     *
     * @return 属性转换器类
     */
    Class<? extends PropertyConverter<?>> value();
}
