package com.team4u.framework.flow.graph;

/**
 * 流程图渲染工厂入口。
 *
 * @author jay.wu
 */
public final class FlowGraphs {

    private FlowGraphs() {
    }

    /**
     * 获取 Mermaid 格式流程图渲染器。
     *
     * @return Mermaid 渲染器
     */
    public static FlowGraphRenderer mermaid() {
        return MermaidFlowGraphRenderer.INSTANCE;
    }

    /**
     * 获取文本树形格式流程图渲染器。
     *
     * @return 文本渲染器
     */
    public static FlowGraphRenderer text() {
        return TextFlowGraphRenderer.INSTANCE;
    }
}
