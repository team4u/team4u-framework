package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.inline.InlineRetryClient;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;

/**
 * 重试代理对象工厂
 * <p>
 * 提供便捷的方法用于创建具备自动重试能力的代理对象。
 */
public class RetryProxyFactory {

    /**
     * 显式指定类型并创建重试代理
     *
     * @param target        原始目标对象
     * @param targetClass   代理需要实现的接口或目标类类型
     * @param inlineClient  内存重试客户端
     * @param managedClient 托管重试客户端
     * @param <T>           目标对象类型
     * @return 增强后的代理对象
     */
    public static <T> T createProxy(T target, Class<T> targetClass, InlineRetryClient inlineClient,
                                    ManagedRetryClient managedClient) {
        return ProxyBuilder.forClass(targetClass)
                .delegate(target)
                .intercept(new RetryInterceptor(inlineClient, managedClient))
                .build();
    }
}
