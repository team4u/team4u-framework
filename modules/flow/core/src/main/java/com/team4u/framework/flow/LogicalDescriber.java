package com.team4u.framework.flow;

import com.team4u.framework.policy.api.KeyedPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * 逻辑 AST 结构描述生成策略。
 *
 * @param <T> 逻辑 AST 节点类型
 * @author jay.wu
 */
interface LogicalDescriber<T extends Logical> extends KeyedPolicy<Class<? extends Logical>> {

    /**
     * 是否为叶子节点。
     *
     * @return 若无需展开子节点则返回 true
     */
    boolean isLeaf();

    /**
     * 将复合子节点逆序压入工作栈。
     *
     * @param logical   逻辑节点
     * @param path      当前节点路径
     * @param workStack 工作栈
     */
    void pushChildren(T logical, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack);

    /**
     * 构建节点结构描述。
     *
     * @param logical     逻辑节点
     * @param path        当前节点路径
     * @param label       节点标签
     * @param resultStack 结果收集栈
     * @return 节点描述
     */
    NodeDescription build(T logical, String path, String label, ArrayList<NodeDescription> resultStack);
}
