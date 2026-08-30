package com.team4u.framework.flow;

import java.util.Objects;
import java.util.Optional;

/**
 * 节点绑定的静态只读描述。
 */
public final class BindingDescriptor {
    private final Optional<Class<?>> contractClass;
    private final Optional<Class<?>> implementationClass;
    private final Optional<String> qualifier;
    private final String kind;

    public BindingDescriptor(Class<?> contractClass, Class<?> implementationClass,
                             String qualifier, String kind) {
        this.contractClass = Optional.ofNullable(contractClass);
        this.implementationClass = Optional.ofNullable(implementationClass);
        this.qualifier = Optional.ofNullable(qualifier);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    public Optional<Class<?>> contractClass() {
        return contractClass;
    }

    public Optional<Class<?>> implementationClass() {
        return implementationClass;
    }

    public Optional<String> qualifier() {
        return qualifier;
    }

    public String kind() {
        return kind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BindingDescriptor that = (BindingDescriptor) o;
        return contractClass.equals(that.contractClass)
                && implementationClass.equals(that.implementationClass)
                && qualifier.equals(that.qualifier)
                && kind.equals(that.kind);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contractClass, implementationClass, qualifier, kind);
    }

    @Override
    public String toString() {
        return "BindingDescriptor[contractClass=" + contractClass
                + ", implementationClass=" + implementationClass
                + ", qualifier=" + qualifier
                + ", kind=" + kind + "]";
    }
}
