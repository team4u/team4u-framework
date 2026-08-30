package com.team4u.framework.flow.test;

import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.FlowTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link FlowTrace} 断言支持。
 *
 * @author jay.wu
 */
public final class FlowTraceAssert {

    private final FlowTrace actual;
    private final List<FlowTrace.Entry> flatEntries;

    FlowTraceAssert(FlowTrace actual) {
        this.actual = Objects.requireNonNull(actual, "FlowTrace must not be null");
        this.flatEntries = flatten(actual.entries());
    }

    private static List<FlowTrace.Entry> flatten(List<FlowTrace.Entry> entries) {
        List<FlowTrace.Entry> result = new ArrayList<>();
        if (entries != null) {
            for (FlowTrace.Entry entry : entries) {
                result.add(entry);
                if (entry.children() != null && !entry.children().isEmpty()) {
                    result.addAll(flatten(entry.children()));
                }
            }
        }
        return result;
    }

    public FlowTraceAssert hasExecutedNode(String nodeId) {
        boolean found = false;
        for (FlowTrace.Entry entry : flatEntries) {
            if (Objects.equals(entry.nodeId(), nodeId)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AssertionError("Expected trace to contain executed node <" + nodeId + ">, but entries were: " + extractNodeIds());
        }
        return this;
    }

    public FlowTraceAssert hasExecutedPath(String nodePath) {
        boolean found = false;
        for (FlowTrace.Entry entry : flatEntries) {
            if (Objects.equals(entry.nodePath(), nodePath)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AssertionError("Expected trace to contain executed node path <" + nodePath + ">, but paths were: " + extractNodePaths());
        }
        return this;
    }

    public FlowTraceAssert hasNodeStatus(String nodeId, FlowResult.Kind expectedStatus) {
        FlowTrace.Entry found = null;
        for (FlowTrace.Entry entry : flatEntries) {
            if (Objects.equals(entry.nodeId(), nodeId)) {
                found = entry;
                break;
            }
        }
        if (found == null) {
            throw new AssertionError("Expected trace to contain node <" + nodeId + "> with status <" + expectedStatus + ">, but node was not executed");
        }
        if (found.status() != expectedStatus) {
            throw new AssertionError("Expected node <" + nodeId + "> to have status <" + expectedStatus + "> but was <" + found.status() + ">");
        }
        return this;
    }

    public FlowTraceAssert hasExecutionOrder(String... expectedNodeIds) {
        if (expectedNodeIds == null || expectedNodeIds.length == 0) {
            return this;
        }
        int currentIndex = 0;
        for (FlowTrace.Entry entry : flatEntries) {
            if (currentIndex < expectedNodeIds.length && Objects.equals(entry.nodeId(), expectedNodeIds[currentIndex])) {
                currentIndex++;
            }
        }
        if (currentIndex < expectedNodeIds.length) {
            throw new AssertionError("Expected execution order " + java.util.Arrays.toString(expectedNodeIds) +
                    " but executed nodes in order were: " + extractNodeIds());
        }
        return this;
    }

    public FlowTraceAssert hasBranchSelected(String chooseNodeId, String branchKey) {
        FlowTrace.Entry found = null;
        for (FlowTrace.Entry entry : flatEntries) {
            if (Objects.equals(entry.nodeId(), chooseNodeId)) {
                found = entry;
                break;
            }
        }
        if (found == null) {
            throw new AssertionError("Expected trace to contain choose node <" + chooseNodeId + ">");
        }
        if (!Objects.equals(found.branchKey(), branchKey)) {
            throw new AssertionError("Expected choose node <" + chooseNodeId + "> to select branch <" + branchKey +
                    "> but was <" + found.branchKey() + ">");
        }
        return this;
    }

    public FlowTraceAssert hasNodeCount(int expectedCount) {
        if (flatEntries.size() != expectedCount) {
            throw new AssertionError("Expected " + expectedCount + " executed nodes, but was " + flatEntries.size() + ": " + extractNodeIds());
        }
        return this;
    }

    private List<String> extractNodeIds() {
        List<String> list = new ArrayList<>();
        for (FlowTrace.Entry entry : flatEntries) {
            list.add(entry.nodeId());
        }
        return list;
    }

    private List<String> extractNodePaths() {
        List<String> list = new ArrayList<>();
        for (FlowTrace.Entry entry : flatEntries) {
            list.add(entry.nodePath());
        }
        return list;
    }
}
