package com.team4u.framework.router.annotation;

import java.lang.annotation.*;

/**
 * 声明式路由注解
 * <p>
 * 标记在接口或方法上，表示该方法的调用将被动态路由到具体的 Bean 实例。
 * 如果标记在类上，该类中所有的方法都将执行路由逻辑（除非方法上有覆盖）。
 * </p>
 *
 * @author jay.wu
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Routed {

    /**
     * 路由策略的唯一标识 (对应配置中心的 router.{routerId})
     */
    String routerId();
}
