package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 默认的节点执行上下文实现。
 *
 * @author jay.wu
 */
final class DefaultStepContext implements StepContext {

    private final String flowId;
    private final String executionId;
    private final String nodeId;
    private final String nodePath;
    private final String invocationId;

    DefaultStepContext(String flowId, String executionId, String nodeId, String nodePath, String invocationId) {
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.executionId = executionId;
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.nodePath = nodePath != null ? nodePath : nodeId;
        this.invocationId = invocationId;
    }

    @Override
    public String flowId() {
        return flowId;
    }

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public String nodePath() {
        return nodePath;
    }

    @Override
    public String invocationId() {
        return invocationId;
    }

    @Override
    public String toString() {
        return "StepContext{flowId='" + flowId + "', nodeId='" + nodeId + "', invocationId='" + invocationId + "'}";
    }
}
