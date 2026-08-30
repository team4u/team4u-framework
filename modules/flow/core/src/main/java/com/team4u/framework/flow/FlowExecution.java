package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 流程单次执行句柄：包含结果与可选的 Trace 诊断信息。
 *
 * @param <O> 成功产物类型
 * @author jay.wu
 */
public final class FlowExecution<O> {

    private final String flowId;
    private final String executionId;
    private final FlowResult<O> result;
    private final FlowTrace trace;

    public FlowExecution(String flowId, String executionId, FlowResult<O> result, FlowTrace trace) {
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.executionId = executionId;
        this.result = Objects.requireNonNull(result, "result must not be null");
        this.trace = trace != null ? trace : FlowTrace.empty();
    }

    public String flowId() {
        return flowId;
    }

    public String executionId() {
        return executionId;
    }

    public FlowResult<O> result() {
        return result;
    }

    public FlowTrace trace() {
        return trace;
    }

    @Override
    public String toString() {
        return "FlowExecution{flowId='" + flowId + '\'' +
                (executionId != null ? ", executionId='" + executionId + '\'' : "") +
                ", result=" + result + '}';
    }
}
