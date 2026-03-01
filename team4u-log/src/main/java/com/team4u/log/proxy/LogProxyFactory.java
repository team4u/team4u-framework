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
     * @param target      被代理的目标对象
     * @param targetClass 对象类型
     * @param <T>         泛型类型
     * @return 代理后的对象
     */
    public static <T> T createProxy(T target, Class<T> targetClass) {
        return ProxyBuilder.forClass(targetClass)
                .withDelegate(target)
                .addInterceptor(new LogTraceInterceptor())
                .build();
    }

    /**
     * 为指定对象创建基于动态配置的日志代理（免侵入，专为第三方类库设计）
     *
     * @param target      被代理的目标对象
     * @param targetClass 对象类型
     * @param <T>         泛型类型
     * @return 代理后的对象
     */
    public static <T> T createDynamicProxy(T target, Class<T> targetClass) {
        return ProxyBuilder.forClass(targetClass)
                .withDelegate(target)
                .addInterceptor(new DynamicLogProxyInterceptor())
                .build();
    }
}
