package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 流程结构只读描述模型。面向 Graph、Test 和诊断，不暴露 Step、Action 等 callback 实例。
 *
 * @author jay.wu
 */
public final class FlowDescription {

    private final String flowId;
    private final List<NodeDescription> nodes;

    public FlowDescription(String flowId, List<NodeDescription> nodes) {
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.nodes = nodes != null ? Collections.unmodifiableList(new ArrayList<>(nodes)) : Collections.emptyList();
    }

    public String flowId() {
        return flowId;
    }

    public List<NodeDescription> nodes() {
        return nodes;
    }

    @Override
    public String toString() {
        return "FlowDescription{flowId='" + flowId + "', nodes=" + nodes + '}';
    }
}
