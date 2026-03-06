package com.team4u.framework.retry.proxy;

import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.proxy.ProxyBuilder;
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
     * 为目标对象创建重试代理
     *
     * @param target  原始业务对象
     * @param backend 租约/重试后端实现（用于任务持久化）
     * @param <T>     业务类型
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, LeaseBackend backend) {
        return createProxy(target, (Class<T>) target.getClass(), backend);
    }

    /**
     * 为指定类别的目标对象创建重试代理
     *
     * @param target      原始业务对象
     * @param targetClass 目标对象的类定义（通常是接口）
     * @param backend     租约/重试后端实现
     * @param <T>         业务类型
     * @return 代理对象
     */
    public static <T> T createProxy(T target, Class<T> targetClass, LeaseBackend backend) {
        return ProxyBuilder.forClass(targetClass)
                .delegate(target)
                .intercept(new RetryInterceptor(backend))
                .build();
    }
}
