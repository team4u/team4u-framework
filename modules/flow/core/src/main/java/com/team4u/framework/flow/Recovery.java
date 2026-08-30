package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 失败恢复 flow 的输入：原始 scope 输入与最终 Failure。
 */
public final class Recovery<I> {
    private final I input;
    private final Failure failure;

    public Recovery(I input, Failure failure) {
        this.input = Objects.requireNonNull(input, "input must not be null");
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public I input() {
        return input;
    }

    public Failure failure() {
        return failure;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Recovery<?> recovery = (Recovery<?>) o;
        return input.equals(recovery.input) && failure.equals(recovery.failure);
    }

    @Override
    public int hashCode() {
        return Objects.hash(input, failure);
    }

    @Override
    public String toString() {
        return "Recovery[input=" + input + ", failure=" + failure + "]";
    }
}
