package com.team4u.framework.flow;

import com.team4u.framework.base.util.Assert;
import com.team4u.framework.base.util.IdUtil;

/**
 * 流程单次执行上下文。
 *
 * @author jay.wu
 */
final class ExecutionContext {

    private final String flowId;
    private String executionId;
    private final boolean traceEnabled;
    private final FlowObserver observer;
    private final TraceCollector traceCollector;

    ExecutionContext(String flowId, String executionId, boolean traceEnabled, FlowObserver observer) {
        Assert.notNull(flowId, "flowId must not be null");
        this.flowId = flowId;
        this.executionId = executionId;
        this.traceEnabled = traceEnabled;
        this.observer = observer;
        this.traceCollector = traceEnabled ? new TraceCollector() : null;
    }

    String flowId() {
        return flowId;
    }

    String executionId() {
        return executionId;
    }

    String getOrCreateExecutionId() {
        if (executionId == null || executionId.isEmpty()) {
            executionId = IdUtil.simpleUUID();
        }
        return executionId;
    }

    boolean isTraceEnabled() {
        return traceEnabled;
    }

    TraceCollector traceCollector() {
        return traceCollector;
    }

    StepContext createStepContext(String nodeId, String nodePath, String nodeAddress) {
        String execId = getOrCreateExecutionId();
        String invocationId = execId + "#" + nodeAddress;
        return new DefaultStepContext(flowId, execId, nodeId, nodePath, invocationId);
    }

    void notifyFlowStarted() {
        if (observer != null) {
            try {
                observer.onEvent(FlowEvent.flowStarted(flowId, executionId));
            } catch (Throwable ignored) {
                // Observer errors are isolated
            }
        }
    }

    void notifyFlowCompleted(FlowResult.Kind status, long durationNanos, StopReason stopReason, FailureContext failure) {
        if (observer != null) {
            try {
                observer.onEvent(FlowEvent.flowCompleted(flowId, executionId, status, durationNanos, stopReason, failure));
            } catch (Throwable ignored) {
                // Observer errors are isolated
            }
        }
    }

    void notifyNodeStarted(String nodeId, String nodePath, NodeKind nodeKind) {
        if (observer != null) {
            try {
                observer.onEvent(FlowEvent.nodeStarted(flowId, executionId, nodeId, nodePath, nodeKind));
            } catch (Throwable ignored) {
                // Observer errors are isolated
            }
        }
    }

    void notifyNodeCompleted(String nodeId, String nodePath, NodeKind nodeKind, FlowResult.Kind status, long durationNanos, StopReason stopReason, FailureContext failure) {
        if (observer != null) {
            try {
                observer.onEvent(FlowEvent.nodeCompleted(flowId, executionId, nodeId, nodePath, nodeKind, status, durationNanos, stopReason, failure));
            } catch (Throwable ignored) {
                // Observer errors are isolated
            }
        }
    }

    FlowTrace buildTrace() {
        if (traceCollector != null) {
            return traceCollector.buildTrace();
        }
        return FlowTrace.empty();
    }
}
