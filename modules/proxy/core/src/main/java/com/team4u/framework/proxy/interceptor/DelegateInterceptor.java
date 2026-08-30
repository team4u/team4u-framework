package com.team4u.framework.proxy.interceptor;

import com.team4u.framework.base.util.ReflectUtil;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.proxy.core.ProxyException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 委托拦截器：将方法调用安全地转发给真正的目标对象
 *
 * @author jay.wu
 */
public class DelegateInterceptor implements MethodInterceptor {

    /**
     * 缓存目标对象的方法映射，避免高并发下频繁的反射查找
     */
    private final ConcurrentMap<Method, Method> methodCache = new ConcurrentHashMap<>();

    /**
     * 使用 volatile 保证多线程可见性（为 HotSwap 预留支持）
     */
    protected volatile Object delegate;

    public DelegateInterceptor(Object delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object currentDelegate = this.delegate;
        if (currentDelegate == null) {
            return invocation.proceed(); // 如果没有委托对象，继续传递给默认处理逻辑
        }

        Method targetMethod = getMethodToInvoke(currentDelegate, invocation.getMethod());

        try {
            // 使用 ReflectUtil 统一执行，处理 accessible
            return ReflectUtil.invoke(currentDelegate, targetMethod, invocation.getArguments());
        } catch (Throwable e) {
            // 递归剥离反射包装异常 (InvocationTargetException) 或工具类包装异常 (RuntimeException)
            Throwable cause = e;
            while (cause.getCause() != null &&
                    (cause instanceof InvocationTargetException
                            || cause.getClass().getName().contains("InvocationTargetRuntimeException")
                            || cause.getClass().getName().contains("UtilException"))) {
                cause = cause.getCause();
                if (cause instanceof InvocationTargetException) {
                    cause = ((InvocationTargetException) cause).getTargetException();
                }
            }
            throw cause;
        }
    }

    /**
     * 获取真正需要调用的 Method 对象
     */
    protected Method getMethodToInvoke(Object target, Method method) {
        // 如果目标类是代理方法所在类的子类或实现，直接使用原方法签名
        if (method.getDeclaringClass().isAssignableFrom(target.getClass())) {
            return method;
        }

        // 否则通过方法名和参数类型在目标对象上动态查找（鸭子类型匹配）
        return methodCache.computeIfAbsent(method, m -> {
            Method found = ReflectUtil.getMethod(target.getClass(), m.getName(), m.getParameterTypes());
            if (found == null) {
                throw new ProxyException("Method not found on delegate object: " + m.getName());
            }
            return found;
        });
    }
}
