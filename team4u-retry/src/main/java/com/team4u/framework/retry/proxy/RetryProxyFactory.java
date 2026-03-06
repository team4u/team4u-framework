package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

/**
 * 重试代理工厂
 * <p>
 * 用于便捷地为业务对象创建具备重试能力的代理。
 *
 * @author jay.wu
 */
public class RetryProxyFactory {

    /**
     * 注册默认的注解快照恢复处理器
     */
    public static void registerDefaultRecoveryHandler() {
        RecoveryHandlerRegistry.ensureDefaultProxyRecoveryHandlerRegistered();
    }

    /**
     * 为指定对象创建仅支持内存重试的代理
     *
     * @param target 目标业务对象
     * @param <T>    业务类型
     * @return 代理对象
     */
    public static <T> T createProxy(T target) {
        return createProxy(target, null);
    }

    /**
     * 为指定对象创建重试代理
     *
     * @param target  目标业务对象
     * @param backend 重试后端，仅需内存重试时可传空
     * @param <T>     业务类型
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, RetryBackend backend) {
        return createProxy(target, (Class<T>) target.getClass(), backend);
    }

    /**
     * 为指定对象创建重试代理
     *
     * @param target      目标业务对象
     * @param targetClass 代理类型（建议传入接口类型）
     * @param backend     重试后端
     * @param <T>         业务类型
     * @return 代理对象
     */
    public static <T> T createProxy(T target, Class<T> targetClass, RetryBackend backend) {
        return ProxyBuilder.forClass(targetClass)
                .delegate(target)
                .intercept(new RetryInterceptor(backend))
                .build();
    }
}
