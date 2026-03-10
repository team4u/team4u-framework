package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.retry.recovery.RecoveryHandler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

/**
 * 恢复处理器 payload 类型解析工具。
 */
final class RecoveryHandlerPayloadTypes {

    private RecoveryHandlerPayloadTypes() {
    }

    static RecoveryHandler<String> requireStringPayload(RecoveryHandler<?> handler, String source) {
        if (handler == null) {
            throw new IllegalArgumentException(source + " must not be null");
        }

        Class<?> payloadType = resolvePayloadType(handler.getClass());
        if (payloadType != String.class) {
            throw new IllegalArgumentException(source
                    + " only supports RecoveryHandler<String>. handler="
                    + handler.getClass().getName()
                    + ", payloadType="
                    + (payloadType == null ? "unresolved" : payloadType.getName()));
        }

        @SuppressWarnings("unchecked")
        RecoveryHandler<String> typedHandler = (RecoveryHandler<String>) handler;
        return typedHandler;
    }

    private static Class<?> resolvePayloadType(Class<?> type) {
        if (type == null || type == Object.class) {
            return null;
        }

        for (Type candidate : type.getGenericInterfaces()) {
            Class<?> resolved = resolveFromType(candidate);
            if (resolved != null) {
                return resolved;
            }
        }

        return resolveFromType(type.getGenericSuperclass());
    }

    private static Class<?> resolveFromType(Type candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) candidate;
            Type rawType = parameterizedType.getRawType();
            if (rawType == RecoveryHandler.class) {
                return toClass(parameterizedType.getActualTypeArguments()[0]);
            }
            return resolveFromType(rawType);
        }
        if (candidate instanceof Class<?>) {
            return resolvePayloadType((Class<?>) candidate);
        }
        return null;
    }

    private static Class<?> toClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            return rawType instanceof Class<?> ? (Class<?>) rawType : null;
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            Type[] upperBounds = wildcardType.getUpperBounds();
            return upperBounds.length == 0 ? null : toClass(upperBounds[0]);
        }
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> variable = (TypeVariable<?>) type;
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? null : toClass(bounds[0]);
        }
        return null;
    }
}
