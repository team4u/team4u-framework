package com.team4u.framework.proxy.core;

import java.util.List;

/**
 * 动态代理引擎抽象接口
 *
 * @author team4u
 */
public interface ProxyEngine {

    /**
     * 引擎是否支持代理目标类型集合
     *
     * @param types 目标类型（类或接口）
     * @return true 若支持
     */
    boolean supports(Class<?>... types);

    /**
     * 创建代理实例
     *
     * @param primaryType  主代理类型（可以是 Class 也可以是 Interface）
     * @param interfaces   代理对象需要额外实现的接口列表
     * @param interceptors 绑定的拦截器链
     * @param <T>          代理类型泛型
     * @return 代理对象实例
     */
    <T> T createProxy(Class<T> primaryType, Class<?>[] interfaces, List<MethodInterceptor> interceptors);
}
