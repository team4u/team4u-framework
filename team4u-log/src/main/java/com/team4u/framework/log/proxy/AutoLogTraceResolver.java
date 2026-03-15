package com.team4u.framework.log.proxy;

import com.team4u.framework.base.util.ReflectUtil;

import java.lang.reflect.Method;

/**
 * 统一解析 {@link AutoLogTrace} 的位置
 */
public final class AutoLogTraceResolver {

    private AutoLogTraceResolver() {
    }

    public static AutoLogTrace resolve(Class<?> targetClass, Method method) {
        if (method == null) {
            return null;
        }

        AutoLogTrace config = method.getAnnotation(AutoLogTrace.class);
        if (config != null) {
            return config;
        }

        if (targetClass != null && targetClass != Object.class) {
            Method targetMethod = ReflectUtil.getMethod(targetClass, method.getName(), method.getParameterTypes());
            if (targetMethod != null) {
                config = targetMethod.getAnnotation(AutoLogTrace.class);
                if (config != null) {
                    return config;
                }
            }

            config = targetClass.getAnnotation(AutoLogTrace.class);
            if (config != null) {
                return config;
            }
        }

        return method.getDeclaringClass().getAnnotation(AutoLogTrace.class);
    }
}
