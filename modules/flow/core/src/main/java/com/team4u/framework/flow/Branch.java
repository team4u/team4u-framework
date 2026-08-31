package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 命名类型化并行分支令牌（Branch Token）。
 *
 * <p>在并行节点（{@link Flow#parallel}）中，用于定义单条独立的子流程分支，
 * 并在汇聚阶段通过 {@link ParallelResults#outcome(Branch)} 或 {@link ParallelResults#value(Branch)}
 * 精确检索指定分支的执行结果。
 * <ul>
 *   <li>{@code name}：分支名称，在同一个并行作用域内必须唯一；</li>
 *   <li>{@code flow}：该分支所执行的子流程。</li>
 * </ul>
 * </p>
 *
 * @param <I> 分支输入参数类型
 * @param <O> 分支输出结果类型
 * @author team4u
 */
public final class Branch<I, O> {
    /** 分支唯一名称标识。 */
    private final String name;
    /** 分支关联的子流程。 */
    private final Flow<I, O> flow;

    /**
     * 内部私有构造器。
     *
     * @param name 分支名称，不能为 null 或空白
     * @param flow 分支子流程，不能为 null
     */
    private Branch(String name, Flow<I, O> flow) {
        this.name = text(name);
        this.flow = Objects.requireNonNull(flow, "flow must not be null");
    }

    /**
     * 创建基于指定子流程的并行分支令牌。
     *
     * @param name 分支名称，在同一并行块内必须唯一，不能为 null 或空白
     * @param flow 分支子流程，不能为 null
     * @param <I>  分支输入类型
     * @param <O>  分支输出类型
     * @return 初始化的 {@link Branch} 实例
     * @throws NullPointerException     当任何参数为 null 时抛出
     * @throws IllegalArgumentException 当 {@code name} 为空白时抛出
     */
    public static <I, O> Branch<I, O> of(String name, Flow<I, O> flow) {
        return new Branch<I, O>(name, flow);
    }

    /**
     * 创建基于单步操作实例的并行分支令牌。
     *
     * @param name      分支名称，在同一并行块内必须唯一，不能为 null 或空白
     * @param operation 操作实例，不能为 null
     * @param <I>       分支输入类型
     * @param <O>       分支输出类型
     * @return 初始化的 {@link Branch} 实例
     * @throws NullPointerException     当任何参数为 null 时抛出
     * @throws IllegalArgumentException 当 {@code name} 为空白时抛出
     */
    public static <I, O> Branch<I, O> of(String name, Operation<I, O> operation) {
        return new Branch<I, O>(name, Flow.step(operation));
    }

    /**
     * 创建基于延迟解析操作类型的并行分支令牌。
     *
     * @param name           分支名称，在同一并行块内必须唯一，不能为 null 或空白
     * @param operationClass 操作契约/实现 Class，不能为 null
     * @param <I>            分支输入类型
     * @param <O>            分支输出类型
     * @return 初始化的 {@link Branch} 实例
     * @throws NullPointerException     当任何参数为 null 时抛出
     * @throws IllegalArgumentException 当 {@code name} 为空白时抛出
     */
    public static <I, O> Branch<I, O> of(
            String name, Class<? extends Operation<I, O>> operationClass) {
        return new Branch<I, O>(name, Flow.step(operationClass));
    }

    /**
     * 创建基于延迟解析限定符操作类型的并行分支令牌。
     *
     * @param name           分支名称，在同一并行块内必须唯一，不能为 null 或空白
     * @param operationClass 操作契约/实现 Class，不能为 null
     * @param qualifier      Spring/Bean 限定符名称，不能为 null 或空白
     * @param <I>            分支输入类型
     * @param <O>            分支输出类型
     * @return 初始化的 {@link Branch} 实例
     * @throws NullPointerException     当任何参数为 null 时抛出
     * @throws IllegalArgumentException 当参数为空白时抛出
     */
    public static <I, O> Branch<I, O> of(
            String name, Class<? extends Operation<I, O>> operationClass, String qualifier) {
        return new Branch<I, O>(name, Flow.step(operationClass, qualifier));
    }

    /**
     * 获取分支名称。
     *
     * @return 分支名称
     */
    public String name() {
        return name;
    }

    /**
     * 获取分支关联的子流程。
     *
     * @return 子流程实例
     */
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

