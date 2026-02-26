package com.team4u.framework.config.core.annotation;

import java.lang.annotation.*;

/**
 * 必填配置注解
 * <p>
 * 将配置项标记为必填。
 * 如果配置源中未找到对应值，框架在运行时将抛出异常。
 * 若字段存在初始值，则不会触发此校验（因为只有配置中实际缺失时才检查）。
 * </p>
 *
 * @author jay.wu
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.FIELD })
public @interface ConfigRequired {
}
