package com.team4u.config.core.annotation;

import java.lang.annotation.*;

/**
 * 配置默认值注解
 * <p>
 * 用于在配置项不存在时提供兜底值。
 *
 * @author fjay
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigDefault {

    /**
     * 默认值的字符串表示形式
     * <p>
     * 框架会尝试将其转换为方法的返回类型。
     *
     * @return 默认值
     */
    String value();
}
