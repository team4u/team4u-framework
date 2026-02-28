package com.team4u.framework.router.api.interceptor;

import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.util.PolicyScanner;

import java.util.Collection;
import java.util.List;

/**
 * 路由拦截器注册中心
 * <p>
 * 负责统一管理、自动发现和按优先级排序路由拦截器。
 * 支持全局单例模式和实例模式。
 * </p>
 */
public class RouteInterceptorRegistry extends OrderedPolicyChain<Void, RouteInterceptor> {

    private static final RouteInterceptorRegistry GLOBAL = new RouteInterceptorRegistry();

    public RouteInterceptorRegistry() {
        super(RouteInterceptor.class);
    }

    /**
     * 获取全局拦截器注册中心实例
     */
    public static RouteInterceptorRegistry global() {
        return GLOBAL;
    }

    /**
     * 自动从 SPI 加载拦截器
     */
    public void autoScan() {
        PolicyScanner.registerFromServiceLoader(this);
    }
}
