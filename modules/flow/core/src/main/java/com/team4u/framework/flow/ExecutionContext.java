package com.team4u.framework.flow;

import java.util.UUID;

/**
 * 流程单次执行上下文。
 *
 * @author jay.wu
 */
final class ExecutionContext {

    private final String flowId;
    private final String executionId;
    private final boolean traceEnabled;
    private final FlowObserver observer;
    private final TraceCollector traceCollector;
    private final String pathPrefix;
    private final String addressPrefix;

    ExecutionContext(String flowId, String executionId, boolean traceEnabled, FlowObserver observer) {
        this(flowId, executionId, traceEnabled, observer, traceEnabled ? new TraceCollector() : null, "", "");
    }

    ExecutionContext(String flowId, String executionId, boolean traceEnabled, FlowObserver observer,
                     TraceCollector traceCollector, String pathPrefix, String addressPrefix) {
        if (flowId == null || flowId.trim().isEmpty()) {
            throw new IllegalArgumentException("flowId must not be null or blank");
        }
        this.flowId = flowId;
        this.executionId = (executionId != null && !executionId.trim().isEmpty()) ? executionId : generateExecutionId();
        this.traceEnabled = traceEnabled;
        this.observer = observer;
        this.traceCollector = traceCollector;
        this.pathPrefix = pathPrefix != null ? pathPrefix : "";
        this.addressPrefix = addressPrefix != null ? addressPrefix : "";
    }

    private static String generateExecutionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    String flowId() {
        return flowId;
    }

    String executionId() {
        return executionId;
    }

    boolean isTraceEnabled() {
        return traceEnabled;
    }

    FlowObserver observer() {
        return observer;
    }

    TraceCollector traceCollector() {
        return traceCollector;
    }

    String pathPrefix() {
        return pathPrefix;
    }

    String addressPrefix() {
        return addressPrefix;
    }

    String qualifyPath(String nodePath) {
        if (pathPrefix == null || pathPrefix.isEmpty()) {
            return nodePath != null ? nodePath : "";
        }
        if (nodePath == null || nodePath.isEmpty()) {
            return pathPrefix;
        }
        return pathPrefix + "/" + nodePath;
    }

    String qualifyAddress(String nodeAddress) {
        if (addressPrefix == null || addressPrefix.isEmpty()) {
            return nodeAddress != null ? nodeAddress : "";
        }
        if (nodeAddress == null || nodeAddress.isEmpty()) {
            return addressPrefix;
        }
        if (nodeAddress.startsWith("/")) {
            return addressPrefix + nodeAddress;
        }
        return addressPrefix + "/" + nodeAddress;
    }

    ExecutionContext childContext(String relativePath, String relativeAddress) {
        String newPath = qualifyPath(relativePath);
        String newAddress = qualifyAddress(relativeAddress);
        return new ExecutionContext(flowId, executionId, traceEnabled, observer, traceCollector, newPath, newAddress);
    }

    StepContext createStepContext(String nodeId, String nodePath, String nodeAddress) {
        String effectivePath = qualifyPath(nodePath);
        String effectiveAddress = qualifyAddress(nodeAddress);
        String invocationId = executionId + "#" + effectiveAddress;
        return new DefaultStepContext(flowId, executionId, nodeId, effectivePath, invocationId);
    }

    void notifyFlowStarted() {
        if (observer != null) {
            try {
                observer.onEvent(FlowEvent.flowStarted(flowId, executionId));
            } catch (RuntimeException ignored) {
                // Observer runtime exceptions are isolated
            }
        }
    }

    void notifyFlowCompleted(FlowResult.Kind status, long durationNanos, StopReason stopReason, FailureContext failure) {
        if (observer != null) {
            try {
                observer.onEvent(FlowEvent.flowCompleted(flowId, executionId, status, durationNanos, stopReason, failure));
            } catch (RuntimeException ignored) {
                // Observer runtime exceptions are isolated
            }
        }
    }

    void notifyNodeStarted(String nodeId, String nodePath, NodeKind nodeKind) {
        if (observer != null) {
            try {
                observer.onEvent(FlowEvent.nodeStarted(flowId, executionId, nodeId, nodePath, nodeKind));
            } catch (RuntimeException ignored) {
                // Observer runtime exceptions are isolated
            }
        }
    }

    void notifyNodeCompleted(String nodeId, String nodePath, NodeKind nodeKind, FlowResult.Kind status, long durationNanos, StopReason stopReason, FailureContext failure) {
        if (observer != null) {
            try {
                observer.onEvent(FlowEvent.nodeCompleted(flowId, executionId, nodeId, nodePath, nodeKind, status, durationNanos, stopReason, failure));
            } catch (RuntimeException ignored) {
                // Observer runtime exceptions are isolated
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
