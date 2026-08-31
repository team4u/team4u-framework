package com.team4u.framework.flow.diagram;

/**
 * 流程图表渲染器工厂与统一门面入口。
 *
 * @author jay.wu
 */
public final class FlowDiagrams {

    private FlowDiagrams() {
    }

    /**
     * 获取确定性、业务友好的标准 Mermaid 流程图渲染器实例。
     */
    public static FlowDiagramRenderer mermaid() {
        return MermaidFlowDiagramRenderer.INSTANCE;
    }

    /**
     * 获取确定性先序遍历单行紧凑文本树渲染器实例。
     */
    public static FlowDiagramRenderer text() {
        return TextFlowDiagramRenderer.INSTANCE;
    }
}
