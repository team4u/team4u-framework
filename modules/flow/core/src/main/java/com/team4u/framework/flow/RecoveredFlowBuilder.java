package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 声明了 {@code recover} 后的流程构造器：在此阶段只能继续声明 {@code ensure} 或调用 {@link #build()} 构建流程。
 *
 * @param <I> 流程输入类型
 * @param <O> 流程输出类型
 * @author jay.wu
 */
public final class RecoveredFlowBuilder<I, O> {

    private final FlowBuilder<I, O> builder;

    RecoveredFlowBuilder(FlowBuilder<I, O> builder) {
        this.builder = Objects.requireNonNull(builder, "builder must not be null");
    }

    /**
     * 声明终态清理动作。
     *
     * @param id               节点 ID，非 null
     * @param completionAction 清理动作，非 null
     * @return 终态构造器
     */
    public EnsuredFlowBuilder<I, O> ensure(String id, CompletionAction<I, O> completionAction) {
        return builder.ensure(id, completionAction);
    }

    /**
     * 声明上下文型终态清理动作。
     *
     * @param id               节点 ID，非 null
     * @param completionAction 上下文型清理动作，非 null
     * @return 终态构造器
     */
    public EnsuredFlowBuilder<I, O> ensure(String id, CompletionAction.Contextual<I, O> completionAction) {
        return builder.ensure(id, completionAction);
    }

    /**
     * 校验并构建不可变 Flow 实例。
     *
     * @return 流程定义
     */
    public Flow<I, O> build() {
        return builder.build();
    }
}
