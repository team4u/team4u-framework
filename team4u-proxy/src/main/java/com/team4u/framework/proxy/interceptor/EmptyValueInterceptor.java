package com.team4u.framework.proxy.interceptor;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.proxy.core.ProxyEngine;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 空值/空对象拦截器：消除 NullPointerException
 *
 * @author team4u
 */
@RequiredArgsConstructor
public class EmptyValueInterceptor implements MethodInterceptor {

    /**
     * 全局空代理对象单例池，防止产生大量无用对象导致 OOM
     */
    private static final ConcurrentMap<Class<?>, Object> EMPTY_INSTANCE_CACHE = new ConcurrentHashMap<>();

    private final Class<?> targetType;
    private final ProxyEngine proxyEngine;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        String methodName = method.getName();

        // 拦截基础核心方法，防止无限递归或抛出异常
        if ("toString".equals(methodName) && method.getParameterCount() == 0) {
            return "EmptyProxy[" + targetType.getSimpleName() + "]";
        }
        if ("hashCode".equals(methodName) && method.getParameterCount() == 0) {
            return System.identityHashCode(invocation.getProxy());
        }
        if ("equals".equals(methodName) && method.getParameterCount() == 1) {
            return invocation.getProxy() == invocation.getArguments()[0];
        }

        Class<?> returnType = method.getReturnType();

        // 1. void 返回类型，直接结束
        if (returnType == void.class) {
            return null;
        }

        // 2. 尝试返回安全的空值（基本类型、集合、字符串等）
        Object safeEmptyValue = resolveSafeEmptyValue(returnType);
        if (safeEmptyValue != null) {
            return safeEmptyValue;
        }

        // 3. 如果是自定义对象，从全局单例缓存获取/创建其嵌套的空对象代理
        return getOrCreateEmptyProxy(returnType);
    }

    private Object resolveSafeEmptyValue(Class<?> type) {
        if (type == String.class) return "";
        if (type == Optional.class) return Optional.empty();

        if (List.class.isAssignableFrom(type)) return Collections.emptyList();
        if (Set.class.isAssignableFrom(type)) return Collections.emptySet();
        if (Map.class.isAssignableFrom(type)) return Collections.emptyMap();

        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }

        // 基础数据类型默认值已在 ReflectiveMethodInvocation 中作为最后屏障处理，但这里做双重保险亦可
        if (type.isPrimitive()) {
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            return 0; // 适配 byte, short, int, long, float, double
        }

        return null;
    }

    private Object getOrCreateEmptyProxy(Class<?> type) {
        // 如果当前引擎不支持代理该类，则只能无奈返回 null
        if (!proxyEngine.supports(type)) {
            return null;
        }

        return EMPTY_INSTANCE_CACHE.computeIfAbsent(type, t ->
                proxyEngine.createProxy(t, new Class<?>[0], Collections.singletonList(new EmptyValueInterceptor(t, proxyEngine)))
        );
    }
}
