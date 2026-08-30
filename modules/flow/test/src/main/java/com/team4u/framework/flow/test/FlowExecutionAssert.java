package com.team4u.framework.flow.test;

import com.team4u.framework.flow.FlowExecution;

import java.util.Objects;

/**
 * {@link FlowExecution} 断言支持。
 *
 * @param <O> 成功产物类型
 * @author jay.wu
 */
public final class FlowExecutionAssert<O> {

    private final FlowExecution<O> actual;

    FlowExecutionAssert(FlowExecution<O> actual) {
        this.actual = Objects.requireNonNull(actual, "FlowExecution must not be null");
    }

    public FlowResultAssert<O> result() {
        return new FlowResultAssert<>(actual.result());
    }

    public FlowTraceAssert trace() {
        return new FlowTraceAssert(actual.trace());
    }

    public FlowExecutionAssert<O> hasExecutionId(String expectedExecutionId) {
        if (!Objects.equals(actual.executionId(), expectedExecutionId)) {
            throw new AssertionError("Expected executionId <" + expectedExecutionId + "> but was <" + actual.executionId() + ">");
        }
        return this;
    }

    public FlowExecutionAssert<O> hasFlowId(String expectedFlowId) {
        if (!Objects.equals(actual.flowId(), expectedFlowId)) {
            throw new AssertionError("Expected flowId <" + expectedFlowId + "> but was <" + actual.flowId() + ">");
        }
        return this;
    }
}
