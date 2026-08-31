package com.team4u.framework.flow;

import java.util.Objects;
import java.util.Optional;

/**
 * 编译后运行时节点的静态描述：path、可选 label、Kind 与绑定契约/实现类/qualifier。
 * 结构节点无绑定，对应字段为 Optional.empty()。
 */
public final class NodeDescriptor {
    private final String path;
    private final Optional<String> label;
    private final Kind kind;
    private final Optional<Class<?>> contractClass;
    private final Optional<Class<?>> implementationClass;
    private final Optional<String> qualifier;

    public NodeDescriptor(String path, Optional<String> label, Kind kind,
                          Optional<Class<?>> contractClass,
                          Optional<Class<?>> implementationClass,
                          Optional<String> qualifier) {
        this.path = text(path, "path");
        this.label = checked(label, "label");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.contractClass = Objects.requireNonNull(contractClass, "contractClass must not be null");
        this.implementationClass = Objects.requireNonNull(implementationClass, "implementationClass must not be null");
        this.qualifier = checked(qualifier, "qualifier");
    }

    public enum Kind { INVOKE, SEQUENCE, ROUTE, FALLBACK, PARALLEL, AWAIT, CONTROL, COMPLETE }

    /** 构造无绑定的结构节点描述符（Invoke 之外的节点）。 */
    static NodeDescriptor structural(String path, String label, Kind kind) {
        return new NodeDescriptor(path, Optional.ofNullable(label), kind,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public String path() {
        return path;
    }

    public Optional<String> label() {
        return label;
    }

    public Kind kind() {
        return kind;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeDescriptor that = (NodeDescriptor) o;
        return path.equals(that.path)
                && label.equals(that.label)
                && kind == that.kind
                && contractClass.equals(that.contractClass)
                && implementationClass.equals(that.implementationClass)
                && qualifier.equals(that.qualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, label, kind, contractClass, implementationClass, qualifier);
    }

    @Override
    public String toString() {
        return "NodeDescriptor[path=" + path + ", label=" + label + ", kind=" + kind
                + ", contractClass=" + contractClass + ", implementationClass=" + implementationClass
                + ", qualifier=" + qualifier + "]";
    }

    private static Optional<String> checked(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name + " must not be null")
                .map(text -> text(text, name));
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
