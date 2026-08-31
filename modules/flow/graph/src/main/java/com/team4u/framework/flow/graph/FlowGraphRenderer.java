package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.desc.FlowDescription;

/**
 * 流程图渲染器接口。
 *
 * @author jay.wu
 */
public interface FlowGraphRenderer {

    /**
     * 渲染流程结构描述。
     *
     * @param description 流程结构描述，非 null
     * @return 渲染结果字符串
     */
    String render(FlowDescription description);
}
