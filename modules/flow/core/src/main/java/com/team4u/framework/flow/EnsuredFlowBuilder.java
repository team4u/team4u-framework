package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 声明了 {@code ensure} 后的流程构造器：在此阶段只能调用 {@link #build()} 构建流程。
 *
 * @param <I> 流程输入类型
 * @param <O> 流程输出类型
 * @author jay.wu
 */
public final class EnsuredFlowBuilder<I, O> {

    private final FlowBuilder<I, O> builder;

    EnsuredFlowBuilder(FlowBuilder<I, O> builder) {
        this.builder = Objects.requireNonNull(builder, "builder must not be null");
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
