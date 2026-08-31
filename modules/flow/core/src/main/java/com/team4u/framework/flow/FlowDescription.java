package com.team4u.framework.flow;

import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 流程逻辑拓扑结构的只读描述模型（常用于生成可视化图表、文本拓扑分析与结构审计）。
 *
 * <p>包含流程标识以及根节点描述树 {@link NodeDescription}。</p>
 *
 * @author team4u
 */
@Getter
@Accessors(fluent = true)
@ToString
public final class FlowDescription {
    /** 流程标识 ID（可选）。 */
    private final String flowId;
    /** 流程根节点的拓扑描述树。 */
    private final NodeDescription root;

    /**
     * 构造流程描述模型。
     *
     * @param flowId 流程标识 ID，可为 null
     * @param root   根节点描述树，不能为 null
     * @throws NullPointerException 当 {@code root} 为 null 时抛出
     */
    public FlowDescription(String flowId, NodeDescription root) {
        this.flowId = flowId;
        this.root = Objects.requireNonNull(root, "root must not be null");
    }
}

