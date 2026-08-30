package com.team4u.framework.flow;

/**
 * 不可变流程观察事件。
 *
 * @author jay.wu
 */
public final class FlowEvent {

    public enum Type {
        FLOW_STARTED,
        FLOW_COMPLETED,
        NODE_STARTED,
        NODE_COMPLETED
    }

    private final String flowId;
    private final String executionId;
    private final String nodeId;
    private final String nodePath;
    private final NodeKind nodeKind;
    private final Type type;
    private final FlowResult.Kind status;
    private final long durationNanos;
    private final StopReason stopReason;
    private final FailureContext failure;
    private final long timestamp;

    private FlowEvent(Builder builder) {
        this.flowId = builder.flowId;
        this.executionId = builder.executionId;
        this.nodeId = builder.nodeId;
        this.nodePath = builder.nodePath;
        this.nodeKind = builder.nodeKind;
        this.type = builder.type;
        this.status = builder.status;
        this.durationNanos = builder.durationNanos;
        this.stopReason = builder.stopReason;
        this.failure = builder.failure;
        this.timestamp = builder.timestamp;
    }

    public static FlowEvent flowStarted(String flowId, String executionId) {
        return new Builder(flowId, executionId, Type.FLOW_STARTED).build();
    }

    public static FlowEvent flowCompleted(String flowId, String executionId, FlowResult.Kind status, long durationNanos, StopReason stopReason, FailureContext failure) {
        return new Builder(flowId, executionId, Type.FLOW_COMPLETED)
                .status(status)
                .durationNanos(durationNanos)
                .stopReason(stopReason)
                .failure(failure)
                .build();
    }

    public static FlowEvent nodeStarted(String flowId, String executionId, String nodeId, String nodePath, NodeKind nodeKind) {
        return new Builder(flowId, executionId, Type.NODE_STARTED)
                .nodeId(nodeId)
                .nodePath(nodePath)
                .nodeKind(nodeKind)
                .build();
    }

    public static FlowEvent nodeCompleted(String flowId, String executionId, String nodeId, String nodePath, NodeKind nodeKind, FlowResult.Kind status, long durationNanos, StopReason stopReason, FailureContext failure) {
        return new Builder(flowId, executionId, Type.NODE_COMPLETED)
                .nodeId(nodeId)
                .nodePath(nodePath)
                .nodeKind(nodeKind)
                .status(status)
                .durationNanos(durationNanos)
                .stopReason(stopReason)
                .failure(failure)
                .build();
    }

    public String flowId() { return flowId; }
    public String executionId() { return executionId; }
    public String nodeId() { return nodeId; }
    public String nodePath() { return nodePath; }
    public NodeKind nodeKind() { return nodeKind; }
    public Type type() { return type; }
    public FlowResult.Kind status() { return status; }
    public long durationNanos() { return durationNanos; }
    public StopReason stopReason() { return stopReason; }
    public FailureContext failure() { return failure; }
    public long timestamp() { return timestamp; }

    @Override
    public String toString() {
        return "FlowEvent{type=" + type + ", flowId='" + flowId + '\'' +
                (nodeId != null ? ", nodeId='" + nodeId + '\'' : "") +
                (status != null ? ", status=" + status : "") + '}';
    }

    public static final class Builder {
        private final String flowId;
        private final String executionId;
        private final Type type;
        private String nodeId;
        private String nodePath;
        private NodeKind nodeKind;
        private FlowResult.Kind status;
        private long durationNanos;
        private StopReason stopReason;
        private FailureContext failure;
        private long timestamp = System.currentTimeMillis();

        private Builder(String flowId, String executionId, Type type) {
            this.flowId = flowId;
            this.executionId = executionId;
            this.type = type;
        }

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder nodePath(String nodePath) { this.nodePath = nodePath; return this; }
        public Builder nodeKind(NodeKind nodeKind) { this.nodeKind = nodeKind; return this; }
        public Builder status(FlowResult.Kind status) { this.status = status; return this; }
        public Builder durationNanos(long durationNanos) { this.durationNanos = durationNanos; return this; }
        public Builder stopReason(StopReason stopReason) { this.stopReason = stopReason; return this; }
        public Builder failure(FailureContext failure) { this.failure = failure; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }

        public FlowEvent build() {
            return new FlowEvent(this);
        }
    }
}
