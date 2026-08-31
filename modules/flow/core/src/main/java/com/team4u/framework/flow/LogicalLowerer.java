package com.team4u.framework.flow;

import com.team4u.framework.policy.api.KeyedPolicy;

import java.util.List;

/**
 * 逻辑 AST 节点到物理执行计划节点的降级编译策略。
 *
 * @param <T> 逻辑 AST 节点类型
 * @author jay.wu
 */
interface LogicalLowerer<T extends Logical> extends KeyedPolicy<Class<? extends Logical>> {

    /**
     * 提取当前逻辑节点的直接子项。
     *
     * @param logical 逻辑节点
     * @param work    当前工作项
     * @return 直接子项列表
     */
    List<Compiler.Child> children(T logical, Compiler.Work work);

    /**
     * 构建当前逻辑节点对应的物理执行计划节点。
     *
     * @param logical 逻辑节点
     * @param work    当前工作项
     * @param context 降级编译上下文
     * @return 物理执行计划节点
     */
    PlanNode build(T logical, Compiler.Work work, LoweringContext context);
}
