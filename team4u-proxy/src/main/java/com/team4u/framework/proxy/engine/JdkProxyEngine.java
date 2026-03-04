package com.team4u.framework.proxy.engine;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.ProxyEngine;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于 JDK 原生反射的动态代理引擎
 * <p>
 * 适用场景：目标类型全部为接口
 * </p>
 *
 * @author jay.wu
 */
public class JdkProxyEngine implements ProxyEngine {

    public static final JdkProxyEngine INSTANCE = new JdkProxyEngine();

    private JdkProxyEngine() {
    }

    @Override
    public boolean supports(Class<?>... types) {
        if (types == null || types.length == 0) {
            return false;
        }
        // JDK Proxy 仅支持接口代理
        return Arrays.stream(types).allMatch(Class::isInterface);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T createProxy(Class<T> primaryType, Class<?>[] interfaces, Object target,
                             List<MethodInterceptor> interceptors) {
        // 合并主接口和附加接口
        Class<?>[] allInterfaces = Stream.concat(Stream.of(primaryType), Arrays.stream(interfaces))
                .distinct()
                .toArray(Class<?>[]::new);

        // 使用当前线程上下文类加载器
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = primaryType.getClassLoader();
        }

        return (T) Proxy.newProxyInstance(
                classLoader,
                allInterfaces,
                new ProxyInvocationHandler(target, interceptors));
    }
}
