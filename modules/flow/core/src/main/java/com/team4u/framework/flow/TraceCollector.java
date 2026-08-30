package com.team4u.framework.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 轨迹收集器：以树形结构收集执行节点信息。
 *
 * @author jay.wu
 */
final class TraceCollector {

    private final List<FlowTrace.Entry> rootEntries = new ArrayList<>();
    private final Deque<Scope> scopeStack = new ArrayDeque<>();

    TraceCollector() {
        scopeStack.push(new Scope(null, null, null, null, null));
    }

    void enterScope(String nodeId, String nodePath, String invocationId, NodeKind kind, String branchKey) {
        Scope scope = new Scope(nodeId, nodePath, invocationId, kind, branchKey);
        scope.startTimeNanos = System.nanoTime();
        scopeStack.push(scope);
    }

    void exitScope(FlowResult.Kind status, StopReason stopReason, FailureContext failure) {
        Scope current = scopeStack.pop();
        long durationNanos = System.nanoTime() - current.startTimeNanos;
        FlowTrace.Entry entry = new FlowTrace.Entry(
                current.nodeId,
                current.nodePath,
                current.invocationId,
                current.kind,
                status,
                durationNanos,
                current.branchKey,
                stopReason,
                failure,
                current.children
        );

        Scope parent = scopeStack.peek();
        if (parent != null && parent.nodeId != null) {
            parent.children.add(entry);
        } else {
            rootEntries.add(entry);
        }
    }

    void recordLeaf(String nodeId, String nodePath, String invocationId, NodeKind kind,
                    FlowResult.Kind status, long durationNanos, String branchKey,
                    StopReason stopReason, FailureContext failure) {
        FlowTrace.Entry entry = new FlowTrace.Entry(
                nodeId,
                nodePath,
                invocationId,
                kind,
                status,
                durationNanos,
                branchKey,
                stopReason,
                failure,
                null
        );
        Scope current = scopeStack.peek();
        if (current != null && current.nodeId != null) {
            current.children.add(entry);
        } else {
            rootEntries.add(entry);
        }
    }

    void recordEntries(List<FlowTrace.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Scope current = scopeStack.peek();
        if (current != null && current.nodeId != null) {
            current.children.addAll(entries);
        } else {
            rootEntries.addAll(entries);
        }
    }

    FlowTrace buildTrace() {
        return new FlowTrace(rootEntries);
    }

    private static final class Scope {
        private final String nodeId;
        private final String nodePath;
        private final String invocationId;
        private final NodeKind kind;
        private final String branchKey;
        private final List<FlowTrace.Entry> children = new ArrayList<>();
        private long startTimeNanos;

        private Scope(String nodeId, String nodePath, String invocationId, NodeKind kind, String branchKey) {
            this.nodeId = nodeId;
            this.nodePath = nodePath;
            this.invocationId = invocationId;
            this.kind = kind;
            this.branchKey = branchKey;
        }
    }
}
