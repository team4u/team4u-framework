package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 流程执行轨迹模型。
 *
 * @author jay.wu
 */
public final class FlowTrace {

    private static final FlowTrace EMPTY = new FlowTrace(Collections.emptyList());

    private final List<Entry> entries;

    public FlowTrace(List<Entry> entries) {
        this.entries = entries != null ? Collections.unmodifiableList(new ArrayList<>(entries)) : Collections.emptyList();
    }

    public static FlowTrace empty() {
        return EMPTY;
    }

    public List<Entry> entries() {
        return entries;
    }

    public static final class Entry {
        private final String nodeId;
        private final String nodePath;
        private final String invocationId;
        private final NodeKind kind;
        private final FlowResult.Kind status;
        private final long durationNanos;
        private final String branchKey;
        private final StopReason stopReason;
        private final FailureContext failure;
        private final List<Entry> children;

        public Entry(String nodeId, String nodePath, String invocationId, NodeKind kind, FlowResult.Kind status,
                     long durationNanos, String branchKey, StopReason stopReason, FailureContext failure, List<Entry> children) {
            this.nodeId = nodeId;
            this.nodePath = nodePath != null ? nodePath : nodeId;
            this.invocationId = invocationId;
            this.kind = kind;
            this.status = status;
            this.durationNanos = durationNanos;
            this.branchKey = branchKey;
            this.stopReason = stopReason;
            this.failure = failure;
            this.children = children != null ? Collections.unmodifiableList(new ArrayList<>(children)) : Collections.emptyList();
        }

        public String nodeId() { return nodeId; }
        public String nodePath() { return nodePath; }
        public String invocationId() { return invocationId; }
        public NodeKind kind() { return kind; }
        public FlowResult.Kind status() { return status; }
        public long durationNanos() { return durationNanos; }
        public String branchKey() { return branchKey; }
        public StopReason stopReason() { return stopReason; }
        public FailureContext failure() { return failure; }
        public List<Entry> children() { return children; }

        @Override
        public String toString() {
            return "Entry{" + nodeId + "(" + kind + ")=" + status + (branchKey != null ? ", branch=" + branchKey : "") + "}";
        }
    }
}
