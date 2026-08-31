package com.team4u.framework.flow;

import java.util.Objects;
import java.util.Optional;

/**
 * 编译解析后的强类型执行绑定描述：持有实际 target 实例、契约接口、实现类、限定符与绑定种类。
 */
public final class ExecutableBinding {
    public enum Kind { OPERATION, POLICY, PERSISTENT_POLICY }

    private final Object instance;
    private final Class<?> contractClass;
    private final Class<?> implementationClass;
    private final Optional<String> qualifier;
    private final Kind kind;

    public ExecutableBinding(Object instance, Class<?> contractClass,
                             Class<?> implementationClass, String qualifier, Kind kind) {
        this.instance = Objects.requireNonNull(instance, "instance must not be null");
        this.contractClass = Objects.requireNonNull(contractClass, "contractClass must not be null");
        this.implementationClass = Objects.requireNonNull(implementationClass, "implementationClass must not be null");
        this.qualifier = Optional.ofNullable(qualifier);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    public Object instance() {
        return instance;
    }

    public Class<?> contractClass() {
        return contractClass;
    }

    public Class<?> implementationClass() {
        return implementationClass;
    }

    public Optional<String> qualifier() {
        return qualifier;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutableBinding that = (ExecutableBinding) o;
        return instance.equals(that.instance)
                && contractClass.equals(that.contractClass)
                && implementationClass.equals(that.implementationClass)
                && qualifier.equals(that.qualifier)
                && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(instance, contractClass, implementationClass, qualifier, kind);
    }

    @Override
    public String toString() {
        return "ExecutableBinding[contractClass=" + contractClass.getName()
                + ", implementationClass=" + implementationClass.getName()
                + ", qualifier=" + qualifier
                + ", kind=" + kind + "]";
    }
}
