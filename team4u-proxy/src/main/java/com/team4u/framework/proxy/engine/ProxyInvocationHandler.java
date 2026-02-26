package com.team4u.framework.proxy.engine;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.ReflectiveMethodInvocation;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 核心调度器：将底层代理引擎的方法调用，桥接到 AOP 拦截器链
 *
 * @author jay.wu
 */
@RequiredArgsConstructor
public class ProxyInvocationHandler implements InvocationHandler {

    private final List<MethodInterceptor> interceptors;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 规避 args 为 null 的情况 (无参方法调用时 JDK 反射可能传入 null)
        Object[] safeArgs = args == null ? new Object[0] : args;

        // 每次方法调用都必须实例化一个新的 Invocation 上下文，因为其中包含基于索引的状态游标
        ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(proxy, method, safeArgs, interceptors);

        // 推进职责链
        return invocation.proceed();
    }
}
