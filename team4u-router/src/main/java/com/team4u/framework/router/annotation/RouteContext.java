package com.team4u.framework.router.annotation;

import java.lang.annotation.*;

/**
 * 路由上下文注解
 * <p>
 * 标记在方法参数上，表示该参数将作为路由规则(表达式)的计算上下文。
 * 一个方法中仅应有一个参数被标记为此注解。
 * </p>
 *
 * @author jay.wu
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RouteContext {
}
