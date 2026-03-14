package com.team4u.framework.router.proxy.annotation;

import java.lang.annotation.*;

/**
 * 声明式路由注解
 * <p>
 * 该注解用于实现原本静态的业务逻辑动态化。标记在接口、类或方法上，表示其执行权将被“路由代理”接管。
 * 代理对象会根据运行时参数及配置的 {@link #routerId()} 指向的路由策略，
 * 将调用流量转发给真正命中的实现类（Bean）。
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
