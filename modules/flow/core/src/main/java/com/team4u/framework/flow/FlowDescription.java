package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 流程结构只读描述模型。
 */
public final class FlowDescription {
    private final String flowId;
    private final NodeDescription root;

    public FlowDescription(String flowId, NodeDescription root) {
        this.flowId = flowId;
        this.root = Objects.requireNonNull(root, "root must not be null");
    }

    public String flowId() {
        return flowId;
    }

    public NodeDescription root() {
        return root;
    }

    @Override
    public String toString() {
        return "FlowDescription[flowId=" + flowId + ", root=" + root + "]";
    }
}
