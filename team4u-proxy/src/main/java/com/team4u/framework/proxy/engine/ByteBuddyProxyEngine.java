package com.team4u.framework.proxy.engine;

import cn.hutool.core.util.ReflectUtil;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.ProxyEngine;
import com.team4u.framework.proxy.core.ProxyException;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * 基于 ByteBuddy 的高性能字节码代理引擎
 * <p>
 * 适用场景：目标类型中包含普通 Class (非 final 类)
 * </p>
 *
 * @author jay.wu
 */
public class ByteBuddyProxyEngine implements ProxyEngine {

    public static final ByteBuddyProxyEngine INSTANCE = new ByteBuddyProxyEngine();

    private ByteBuddyProxyEngine() {
    }

    @Override
    public boolean supports(Class<?>... types) {
        if (types == null || types.length == 0) {
            return false;
        }
        // 不能代理 final 类
        return Arrays.stream(types).noneMatch(clazz -> Modifier.isFinal(clazz.getModifiers()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T createProxy(Class<T> primaryType, Class<?>[] interfaces, Object target,
                             List<MethodInterceptor> interceptors) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = primaryType.getClassLoader();
            }

            Class<? extends T> proxyClass = new ByteBuddy()
                    // 继承主类
                    .subclass(primaryType)
                    // 实现附加接口
                    .implement(interfaces)
                    // 拦截所有方法，但必须排除 finalize() 方法以防止内存泄漏 (GC 异常)
                    .method(ElementMatchers.any().and(ElementMatchers.not(ElementMatchers.isFinalizer())))
                    // 使用适配器，将拦截委托给统一的 JdkInvocationHandler 逻辑
                    .intercept(InvocationHandlerAdapter.of(new ProxyInvocationHandler(target, interceptors)))
                    // 生成并加载字节码
                    .make()
                    .load(classLoader)
                    .getLoaded();

            // 使用 Hutool 反射工具类实例化，支持更复杂的构造场景
            return ReflectUtil.newInstance(proxyClass);

        } catch (Exception e) {
            throw new ProxyException("Failed to create ByteBuddy proxy for target: " + primaryType.getName(), e);
        }
    }
}
