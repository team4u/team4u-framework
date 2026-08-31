package com.team4u.framework.flow.diagram;

import com.team4u.framework.flow.desc.FlowDescription;

/**
 * 流程拓扑图表渲染器接口。
 *
 * <p>负责将流程只读描述模型 {@link FlowDescription} 渲染为目标文本或脚本表示（如 Mermaid 流程图脚本、先序遍历紧凑文本树等）。</p>
 *
 * @author jay.wu
 */
public interface FlowDiagramRenderer {

    /**
     * 渲染指定的流程描述模型。
     *
     * @param description 流程只读静态描述模型，非空
     * @return 渲染后的目标文本/脚本内容
     */
    String render(FlowDescription description);
}
