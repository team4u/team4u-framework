package com.team4u.framework.retry.proxy;

import lombok.Data;

import java.lang.reflect.Method;

/**
 * 统一解析重试拦截与回放所需的方法元数据。
 */
public final class RetryMethodResolver {

    private RetryMethodResolver() {
    }

    public static ResolvedRetryMethod resolve(Method invocationMethod, Class<?> targetClass) {
        Method specificMethod = findMostSpecificMethod(invocationMethod, targetClass);
        Method effectiveMethod = resolveBridgeMethod(specificMethod);
        Retryable retryable = findRetryable(invocationMethod, effectiveMethod, targetClass);
        Class<?> recoveryTargetType = resolveRecoveryTargetType(invocationMethod, targetClass);
        return new ResolvedRetryMethod(effectiveMethod, retryable, recoveryTargetType);
    }

    public static Method findMostSpecificMethod(Method method, Class<?> targetClass) {
        if (method == null || targetClass == null) {
            return method;
        }
        Method candidate = findMethodInHierarchy(targetClass, method.getName(), method.getParameterTypes());
        return candidate != null ? candidate : method;
    }

    private static Method findMethodInHierarchy(Class<?> type, String name, Class<?>[] parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            Method method = findMethodInHierarchy(interfaceType, name, parameterTypes);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static Method resolveBridgeMethod(Method method) {
        if (method == null || !method.isBridge()) {
            return method;
        }
        Method[] declaredMethods = method.getDeclaringClass().getDeclaredMethods();
        for (Method candidate : declaredMethods) {
            if (candidate.isBridge()) {
                continue;
            }
            if (!candidate.getName().equals(method.getName())) {
                continue;
            }
            if (candidate.getParameterCount() != method.getParameterCount()) {
                continue;
            }
            if (!method.getReturnType().isAssignableFrom(candidate.getReturnType())) {
                continue;
            }
            if (matchesBridgeSignature(method, candidate)) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return method;
    }

    private static boolean matchesBridgeSignature(Method bridgeMethod, Method candidate) {
        Class<?>[] bridgeParameterTypes = bridgeMethod.getParameterTypes();
        Class<?>[] candidateParameterTypes = candidate.getParameterTypes();
        for (int i = 0; i < bridgeParameterTypes.length; i++) {
            if (!bridgeParameterTypes[i].isAssignableFrom(candidateParameterTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private static Retryable findRetryable(Method invocationMethod, Method effectiveMethod, Class<?> targetClass) {
        Retryable retryable = invocationMethod.getAnnotation(Retryable.class);
        if (retryable != null) {
            return retryable;
        }
        if (effectiveMethod != null && effectiveMethod != invocationMethod) {
            retryable = effectiveMethod.getAnnotation(Retryable.class);
            if (retryable != null) {
                return retryable;
            }
        }
        if (targetClass != null) {
            retryable = targetClass.getAnnotation(Retryable.class);
            if (retryable != null) {
                return retryable;
            }
        }
        return invocationMethod.getDeclaringClass().getAnnotation(Retryable.class);
    }

    private static Class<?> resolveRecoveryTargetType(Method invocationMethod, Class<?> targetClass) {
        if (invocationMethod != null && invocationMethod.getDeclaringClass() != Object.class) {
            return invocationMethod.getDeclaringClass();
        }
        return targetClass;
    }

    @Data
    public static final class ResolvedRetryMethod {
        private final Method effectiveMethod;
        private final Retryable retryable;
        private final Class<?> recoveryTargetType;
    }
}
