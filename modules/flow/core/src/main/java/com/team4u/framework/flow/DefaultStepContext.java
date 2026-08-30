package com.team4u.framework.flow;

import com.team4u.framework.base.util.Assert;

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
        Assert.notBlank(flowId, "flowId must not be null or blank");
        Assert.notBlank(nodeId, "nodeId must not be null or blank");
        this.flowId = flowId;
        this.executionId = executionId;
        this.nodeId = nodeId;
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
