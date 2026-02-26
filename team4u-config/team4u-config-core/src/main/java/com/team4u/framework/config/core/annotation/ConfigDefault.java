package com.team4u.framework.config.core.annotation;

import java.lang.annotation.*;

/**
 * 配置默认值注解
 * <p>
 * 用于在配置项缺失且未通过其他方式提供值时，指定一个兜底的默认值。
 * 框架会自动将此字符串形式的默认值转换为方法定义的返回类型。
 * </p>
 *
 * @author jay.wu
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigDefault {

    /**
     * 默认值的字符串表示形式
     *
     * @return 默认值字符串
     */
    String value();
}
