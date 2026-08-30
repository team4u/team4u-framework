package com.team4u.framework.ratelimiter.proxy;

import com.team4u.framework.proxy.ProxyBuilder;

/**
 * 限流代理对象工厂（非 Spring 场景的编程式入口）
 * <p>
 * Spring 场景经 {@code @EnableRateLimit} 的 BeanPostProcessor 自动装配；
 * 本工厂仅保留直接编程创建代理的便捷方法，构建逻辑全部委托
 * {@link ProxyBuilder}：目标为接口实现时经 {@link ProxyBuilder#forClass(Class)}
 * 以 JDK 代理创建（见 {@link #proxy(Object, Class)}），普通类走 ByteBuddy
 * 子类代理（final 类无法代理，构建期抛出代理异常）。
 * </p>
 *
 * @author jay.wu
 */
public final class RateLimitProxyFactory {

    private RateLimitProxyFactory() {
    }

    /**
     * 为目标对象创建限流代理（按目标具体类型构建）
     *
     * @param target 原始目标对象
     * @param <T>    目标对象类型
     * @return 增强后的代理对象
     */
    public static <T> T proxy(T target) {
        return ProxyBuilder.proxy(target, new RateLimitInterceptor());
    }

    /**
     * 为目标对象创建指定接口类型的限流代理（JDK 代理）
     *
     * @param target      原始目标对象
     * @param targetClass 代理需要实现的接口类型
     * @param <T>         接口类型
     * @return 增强后的代理对象
     */
    public static <T> T proxy(Object target, Class<T> targetClass) {
        return ProxyBuilder.forClass(targetClass)
                .delegate(target)
                .intercept(new RateLimitInterceptor())
                .build();
    }
}
