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
public class RouteInterceptorRegistry {

    private static final RouteInterceptorRegistry GLOBAL = new RouteInterceptorRegistry();

    private final OrderedPolicyChain<Void, RouteInterceptor> chain;

    public RouteInterceptorRegistry() {
        this.chain = new OrderedPolicyChain<>(RouteInterceptor.class);
    }

    /**
     * 获取全局拦截器注册中心实例
     */
    public static RouteInterceptorRegistry global() {
        return GLOBAL;
    }

    /**
     * 注册单个拦截器
     */
    public void register(RouteInterceptor interceptor) {
        chain.register(interceptor);
    }

    /**
     * 批量注册拦截器
     */
    public void registerAll(Collection<? extends RouteInterceptor> interceptors) {
        chain.addAll(interceptors);
    }

    /**
     * 自动从 SPI 和指定包路径加载拦截器
     */
    public void autoScan() {
        PolicyScanner.registerFromServiceLoader(chain);
        PolicyScanner.scanAndRegister(chain, "com.team4u.framework.router");
    }

    /**
     * 获取按优先级排序后的拦截器列表
     */
    public List<RouteInterceptor> getInterceptors() {
        return chain.getPolicies();
    }
}
