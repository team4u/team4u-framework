package com.team4u.log.proxy;

import com.team4u.framework.proxy.ProxyBuilder;

/**
 * 日志代理工厂
 * <p>
 * 使用 team4u-proxy 为业务对象生成动态代理，集成日志追踪功能。
 */
public class LogProxyFactory {

    /**
     * 为指定对象创建日志代理
     *
     * @param target 目标对象
     * @param <T>    对象类型
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target) {
        return createProxy(target, (Class<T>) target.getClass());
    }

    /**
     * 为指定对象创建日志代理
     *
     * @param target      目标对象
     * @param targetClass 代理类型（用于支持接口或父类代理）
     * @param <T>         对象类型
     * @return 代理对象
     */
    public static <T> T createProxy(T target, Class<T> targetClass) {
        return ProxyBuilder.forClass(targetClass)
                .withDelegate(target)
                .addInterceptor(new LogTraceInterceptor())
                .build();
    }

    /**
     * 为指定对象创建动态配置驱动的日志代理
     *
     * @param target 目标对象
     * @param <T>    对象类型
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createDynamicProxy(T target) {
        return createDynamicProxy(target, (Class<T>) target.getClass());
    }

    /**
     * 为指定对象创建动态配置驱动的日志代理
     *
     * @param target      目标对象
     * @param targetClass 代理类型
     * @param <T>         对象类型
     * @return 代理对象
     */
    public static <T> T createDynamicProxy(T target, Class<T> targetClass) {
        return ProxyBuilder.forClass(targetClass)
                .withDelegate(target)
                .addInterceptor(new DynamicLogProxyInterceptor())
                .build();
    }
}
