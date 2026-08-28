package com.team4u.framework.ratelimiter.proxy;

import com.team4u.framework.base.util.ReflectUtil;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.ratelimiter.api.RateLimitException;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.api.RateLimiters;


import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 限流拦截器：识别方法上的 {@link RateLimit} 注解并按检查点裁决
 * <p>
 * 检查上下文 = 方法参数名 → 参数值（要求编译保留参数名）；注解可标注在
 * 实现方法或接口方法上（注解解析沿「方法 → 目标类同名方法 → 接口层次」查找）。
 * 被拒绝时按 {@link RateLimit#reject()} 处置：EXCEPTION 抛 {@link RateLimitException}；
 * NULL_VALUE 返回 null/基本类型默认值，void 方法直接拦截不执行。
 * 引擎取自 {@link RateLimiters} 全局门面（init 或懒加载默认引擎）。
 * </p>
 *
 * @author jay.wu
 */
public class RateLimitInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Class<?> targetClass = invocation.getTarget() == null
                ? method.getDeclaringClass() : invocation.getTarget().getClass();
        RateLimit annotation = resolveAnnotation(method, targetClass);
        if (annotation == null) {
            return invocation.proceed();
        }

        RateLimitResult result = RateLimiters.acquire(pointOf(annotation),
                argsContext(invocation, targetClass), annotation.permits());
        if (!result.isAllowed()) {
            if (annotation.reject() == RateLimitReject.NULL_VALUE) {
                return defaultValueOf(method.getReturnType());
            }
            // 拒绝在本边界转为异常：被代理方法的签名携带不了裁决结果，
            // 异常是穿越该边界的传输手段（编程式路径由调用方自行决定抛什么）
            throw new RateLimitException(result);
        }
        return invocation.proceed();
    }

    /**
     * 解析注解生效的检查点：{@link RateLimit#value()} 与 {@link RateLimit#point()} 互为别名，
     * 至少设置一个；同时设置时必须一致，否则视为配置错误
     */
    static String pointOf(RateLimit annotation) {
        String value = annotation.value();
        String point = annotation.point();
        if (value.isEmpty() && point.isEmpty()) {
            throw new IllegalStateException("RateLimit annotation requires point|value to be set");
        }
        if (!value.isEmpty() && !point.isEmpty() && !value.equals(point)) {
            throw new IllegalStateException("RateLimit point and value must be consistent"
                    + "|point=" + point + "|value=" + value);
        }
        return value.isEmpty() ? point : value;
    }

    /**
     * 注解解析：方法自身 → 目标类同名方法 → 声明类/目标类的接口层次（适配 JDK 代理
     * 场景下注解仅标注于实现方法、或仅标注于接口方法的两种情形）
     */
    public static RateLimit resolveAnnotation(Method method, Class<?> targetClass) {
        RateLimit annotation = method.getAnnotation(RateLimit.class);
        if (annotation != null) {
            return annotation;
        }
        annotation = findOnClass(targetClass, method.getName(), method.getParameterTypes());
        if (annotation != null) {
            return annotation;
        }
        return findOnClass(method.getDeclaringClass(), method.getName(), method.getParameterTypes());
    }

    private static RateLimit findOnClass(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
        if (clazz == null) {
            return null;
        }
        try {
            Method candidate = clazz.getMethod(methodName, paramTypes);
            RateLimit annotation = candidate.getAnnotation(RateLimit.class);
            if (annotation != null) {
                return annotation;
            }
        } catch (NoSuchMethodException ignored) {
            // 该类未声明此方法，继续查接口与父类
        }
        for (Class<?> intf : clazz.getInterfaces()) {
            RateLimit annotation = findOnClass(intf, methodName, paramTypes);
            if (annotation != null) {
                return annotation;
            }
        }
        return findOnClass(clazz.getSuperclass(), methodName, paramTypes);
    }

    /**
     * 方法参数组装为检查上下文（参数名 → 参数值）；参数名不可用时为空上下文
     */
    private static Map<String, Object> argsContext(MethodInvocation invocation, Class<?> targetClass) {
        Map<String, Object> context = new LinkedHashMap<>();
        Parameter[] parameters = ReflectUtil.getParameters(targetClass, invocation.getMethod());
        Object[] arguments = invocation.getArguments();
        if (parameters == null || arguments == null) {
            return context;
        }
        for (int i = 0; i < parameters.length && i < arguments.length; i++) {
            context.put(parameters[i].getName(), arguments[i]);
        }
        return context;
    }

    /**
     * 基本类型默认值（NULL_VALUE 拒绝策略下返回）；对象类型返回 null
     */
    static Object defaultValueOf(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
