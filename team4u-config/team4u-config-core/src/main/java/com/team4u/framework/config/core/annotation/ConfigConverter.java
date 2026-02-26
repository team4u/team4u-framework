package com.team4u.framework.config.core.annotation;

import com.team4u.framework.config.core.convert.PropertyConverter;

import java.lang.annotation.*;

/**
 * 配置转换器注解
 * <p>
 * 用于在代理接口的方法上指定自定义转换逻辑。
 * 框架将使用指定的 {@link PropertyConverter} 实现类，将配置源中的原始字符串转换为目标返回类型。
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface ConfigConverter {

    /**
     * 指定自定义属性转换器类
     *
     * @return 实现了 PropertyConverter 接口的转换器类
     */
    Class<? extends PropertyConverter<?>> value();
}
