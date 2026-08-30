package com.team4u.framework.flow;

import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Local/Durable 投影期一次性解析 class 与 qualifier 绑定的扩展点。
 * {@link #implementationClass} 在 Spring 代理场景下回退到首个非扩展点接口。
 */
@FunctionalInterface
public interface OperationResolver {
    Object resolve(Class<?> contract, String qualifier);

    default Class<?> implementationClass(Object resolved) {
        Objects.requireNonNull(resolved, "resolved must not be null");
        Class<?> type = resolved.getClass();
        if (Proxy.isProxyClass(type)) {
            for (Class<?> candidate : type.getInterfaces()) {
                if (candidate != Operation.class && candidate != Policy.class
                        && candidate != PersistentPolicy.class) {
                    return candidate;
                }
            }
        }
        return type;
    }

    /** 无解析器的占位实现，命中时直接抛 IllegalStateException。 */
    static OperationResolver rejecting() {
        return new OperationResolver() {
            @Override
            public Object resolve(Class<?> contract, String qualifier) {
                throw new IllegalStateException("No resolver for " + contract.getName()
                        + (qualifier == null ? "" : "[" + qualifier + "]"));
            }
        };
    }
}
