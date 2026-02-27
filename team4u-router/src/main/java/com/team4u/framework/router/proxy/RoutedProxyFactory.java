package com.team4u.framework.router.proxy;

import com.team4u.framework.proxy.ProxyBuilder;

/**
 * 路由代理工厂
 * <p>
 * 提供一行代码创建路由代理的便捷方法。
 * </p>
 *
 * @author jay.wu
 */
public class RoutedProxyFactory {

    private static final RoutedMethodInterceptor SHARED_INTERCEPTOR = new RoutedMethodInterceptor();

    /**
     * 为指定接口创建一个声明式路由代理实例
     *
     * @param interfaceClass 目标接口
     * @param <T>            接口类型
     * @return 代理实例
     */
    public static <T> T createProxy(Class<T> interfaceClass) {
        return ProxyBuilder.forClass(interfaceClass)
                .asEmptyObject()
                .addInterceptor(SHARED_INTERCEPTOR)
                .build();
    }
}
