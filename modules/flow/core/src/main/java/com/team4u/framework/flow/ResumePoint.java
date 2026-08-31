package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 类型化挂起点的稳定标识。name 在同一 Flow 内唯一，用于 Await 暂停与 resume 匹配。
 */
public final class ResumePoint<R> {
    private final String name;

    private ResumePoint(String name) {
        this.name = text(name);
    }

    public static <R> ResumePoint<R> named(String name) {
        return new ResumePoint<R>(name);
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResumePoint<?> that = (ResumePoint<?>) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "ResumePoint[" + name + "]";
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "name must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("name must not be blank");
        return value;
    }
}
