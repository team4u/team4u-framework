package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.backend.RetryBackend;

/**
 * 重试代理对象工厂
 * <p>
 * 提供便捷的方法用于创建具备自动重试能力的代理对象。
 */
public class RetryProxyFactory {

    /**
     * 为目标对象创建重试代理
     *
     * @param target 原始目标对象
     * @param <T>    目标对象类型
     * @return 增强后的代理对象
     */
    public static <T> T createProxy(T target) {
        return createProxy(target, null);
    }

    /**
     * 为目标对象创建支持指定重试后端的代理
     *
     * @param target  原始目标对象
     * @param backend 重试持久化后端（若为 null 则仅支持内存模式重试）
     * @param <T>     目标对象类型
     * @return 增强后的代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, RetryBackend backend) {
        return createProxy(target, (Class<T>) target.getClass(), backend);
    }

    /**
     * 显式指定类型并创建重试代理
     *
     * @param target      原始目标对象
     * @param targetClass 代理需要实现的接口或目标类类型
     * @param backend     重试持久化后端
     * @param <T>         目标对象类型
     * @return 增强后的代理对象
     */
    public static <T> T createProxy(T target, Class<T> targetClass, RetryBackend backend) {
        return ProxyBuilder.forClass(targetClass)
                .delegate(target)
                .intercept(new RetryInterceptor(backend))
                .build();
    }
}
