package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 流程作用域完成上下文：提供对成功产物、停止原因或失败信息的受约束访问。
 *
 * @param <O> 成功输出类型
 * @author jay.wu
 */
public final class CompletionContext<O> {

    private final FlowResult<O> result;

    public CompletionContext(FlowResult<O> result) {
        this.result = Objects.requireNonNull(result, "FlowResult must not be null");
    }

    public FlowResult.Kind kind() {
        return result.kind();
    }

    public boolean isSucceeded() {
        return result.isSucceeded();
    }

    public boolean isStopped() {
        return result.isStopped();
    }

    public boolean isFailed() {
        return result.isFailed();
    }

    public O value() {
        return result.value();
    }

    public StopReason stopReason() {
        return result.stopReason();
    }

    public FailureContext failure() {
        return result.failure();
    }

    public FlowResult<O> result() {
        return result;
    }

    @Override
    public String toString() {
        return "CompletionContext{" + result + '}';
    }
}
