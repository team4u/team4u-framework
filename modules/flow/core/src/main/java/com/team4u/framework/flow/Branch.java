package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 带名称的类型化分支令牌，同时用于 Parallel 声明与结果按 token 查找。
 * name 在同一 Parallel 内必须唯一，flow 不可为 null。
 */
public final class Branch<I, O> {
    private final String name;
    private final Flow<I, O> flow;

    private Branch(String name, Flow<I, O> flow) {
        this.name = text(name);
        this.flow = Objects.requireNonNull(flow, "flow must not be null");
    }

    public static <I, O> Branch<I, O> of(String name, Flow<I, O> flow) {
        return new Branch<I, O>(name, flow);
    }

    public static <I, O> Branch<I, O> of(String name, Operation<I, O> operation) {
        return new Branch<I, O>(name, Flow.step(operation));
    }

    public static <I, O> Branch<I, O> of(
            String name, Class<? extends Operation<I, O>> operationClass) {
        return new Branch<I, O>(name, Flow.step(operationClass));
    }

    public static <I, O> Branch<I, O> of(
            String name, Class<? extends Operation<I, O>> operationClass, String qualifier) {
        return new Branch<I, O>(name, Flow.step(operationClass, qualifier));
    }

    public String name() {
        return name;
    }

    Flow<I, O> flow() {
        return flow;
    }

    @Override
    public String toString() {
        return "Branch[" + name + "]";
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "name must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("name must not be blank");
        return value;
    }
}
