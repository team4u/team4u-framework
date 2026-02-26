package com.team4u.framework.proxy.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 基于反射的职责链推进器
 * <p>
 * 负责维护当前执行的索引，并依次调用所有的 MethodInterceptor。
 * </p>
 *
 * @author team4u
 */
@RequiredArgsConstructor
public class ReflectiveMethodInvocation implements MethodInvocation {

    @Getter
    private final Object proxy;

    @Getter
    private final Method method;

    @Getter
    private final Object[] arguments;

    private final List<MethodInterceptor> interceptors;

    /**
     * 当前正在执行的拦截器索引
     */
    private int currentInterceptorIndex = -1;

    @Override
    public Object proceed() throws Throwable {
        // 如果拦截器链已经走完
        if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
            return invokeJoinPoint();
        }

        // 获取下一个拦截器并执行
        MethodInterceptor interceptor = this.interceptors.get(++this.currentInterceptorIndex);
        return interceptor.invoke(this);
    }

    /**
     * 拦截器链到达末尾的默认收尾逻辑。
     */
    private Object invokeJoinPoint() {
        String methodName = method.getName();
        int paramCount = method.getParameterCount();

        // 兜底处理基础 Object 方法
        if ("toString".equals(methodName) && paramCount == 0) {
            return "Proxy[" + method.getDeclaringClass().getSimpleName() + "]@" + Integer.toHexString(System.identityHashCode(proxy));
        }
        if ("hashCode".equals(methodName) && paramCount == 0) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(methodName) && paramCount == 1) {
            return proxy == arguments[0];
        }

        Class<?> returnType = this.method.getReturnType();
        if (returnType == void.class) {
            return null;
        }
        return getDefaultValue(returnType);
    }

    /**
     * 获取基础数据类型的安全默认值
     */
    private Object getDefaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
