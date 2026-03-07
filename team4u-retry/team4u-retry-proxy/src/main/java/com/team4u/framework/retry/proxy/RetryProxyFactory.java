package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.recovery.RetryTaskTypes;
import com.team4u.framework.retry.recovery.SnapshotRecoveryHandler;

/**
 * 重试代理工厂。
 */
public class RetryProxyFactory {

    private static final Object DEFAULT_RECOVERY_HANDLER_MONITOR = new Object();

    public static void registerDefaultRecoveryHandler() {
        if (RecoveryHandlerRegistry.global().get(RetryTaskTypes.DEFAULT_PROXY_RECOVERY).isPresent()) {
            return;
        }
        synchronized (DEFAULT_RECOVERY_HANDLER_MONITOR) {
            if (RecoveryHandlerRegistry.global().get(RetryTaskTypes.DEFAULT_PROXY_RECOVERY).isPresent()) {
                return;
            }
            RecoveryHandlerRegistry.global().register(new SnapshotRecoveryHandler(RetryTaskTypes.DEFAULT_PROXY_RECOVERY));
        }
    }

    public static <T> T createProxy(T target) {
        return createProxy(target, null);
    }

    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, RetryBackend backend) {
        return createProxy(target, (Class<T>) target.getClass(), backend);
    }

    public static <T> T createProxy(T target, Class<T> targetClass, RetryBackend backend) {
        return ProxyBuilder.forClass(targetClass)
                .delegate(target)
                .intercept(new RetryInterceptor(backend))
                .build();
    }
}
