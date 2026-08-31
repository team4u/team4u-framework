package com.team4u.framework.flow.compiler;

import com.team4u.framework.policy.api.KeyedPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import com.team4u.framework.flow.spi.ExecutableFlowVisitor;

/**
 * 物理执行计划节点（PlanNode）投影到目标访问者（ExecutableFlowVisitor）的策略。
 *
 * @param <T> 物理节点类型
 * @author jay.wu
 */
public interface PlanNodeProjector<T extends PlanNode> extends KeyedPolicy<Class<? extends PlanNode>> {

    /**
     * 将子节点逆序压入工作栈。
     *
     * @param node      物理节点
     * @param workStack 工作栈
     */
    void pushChildren(T node, ArrayDeque<ExecutableProjector.WorkItem> workStack);

    /**
     * 收集子节点投影结果并构建当前节点的最终投影。
     *
     * @param node        物理节点
     * @param resultStack 结果收集栈
     * @param visitor     可执行流程投影访问者
     * @param <R>         产物类型
     * @return 投影产物
     */
    <R> R build(T node, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor);
}
