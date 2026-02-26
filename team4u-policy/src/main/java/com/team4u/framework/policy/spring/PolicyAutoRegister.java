package com.team4u.framework.policy.spring;

import java.lang.annotation.*;

/**
 * 策略自动注册标记
 * <p>
 * 标注在 PolicyRegistry 的 Bean 方法或类上，指示框架从 Spring 容器自动拉取对应的策略。
 *
 * @author jay.wu
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PolicyAutoRegister {
}
