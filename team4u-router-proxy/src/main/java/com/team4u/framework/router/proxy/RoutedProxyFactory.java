package com.team4u.framework.router.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.router.RoutingManager;

/**
 * 路由代理工厂
 * <p>
 * 提供一行代码创建路由代理的便捷方法。
 * </p>
 *
 * @author jay.wu
 */
public class RoutedProxyFactory {

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
                .addInterceptor(RoutedMethodInterceptor.global())
                .build();
    }

    /**
     * 为指定接口创建一个声明式路由代理实例
     *
     * @param interfaceClass 目标接口
     * @param routingManager 自定义路由管理器
     * @param <T>            接口类型
     * @return 代理实例
     */
    public static <T> T createProxy(Class<T> interfaceClass, RoutingManager routingManager) {
        return createProxy(interfaceClass, routingManager, null);
    }

    /**
     * 为指定接口创建一个声明式路由代理实例
     */
    public static <T> T createProxy(Class<T> interfaceClass,
                                    RoutingManager routingManager,
                                    BeanResolver beanResolver) {
        return ProxyBuilder.forClass(interfaceClass)
                .asEmptyObject()
                .addInterceptor(new RoutedMethodInterceptor(routingManager, beanResolver))
                .build();
    }
}
