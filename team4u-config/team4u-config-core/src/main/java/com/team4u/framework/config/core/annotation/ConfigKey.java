package com.team4u.framework.config.core.annotation;

import java.lang.annotation.*;

/**
 * 配置键注解
 * <p>
 * 用于显式指定代理接口方法对应的配置键（Key），从而跳过默认的命名转换推断逻辑。
 * 支持以下两种定义方式：
 * <ul>
 *     <li>相对路径：普通字符串，会与类级别的前缀叠加</li>
 *     <li>绝对路径：以点号 "." 开头的字符串，将忽略类级别的前缀</li>
 * </ul>
 * </p>
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
     * @return 显式定义的配置键名
     */
    String value();
}
