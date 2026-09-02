package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.model.FlowSpec;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程静态类型检查上下文接口。
 *
 * @author jay.wu
 */
public interface TypeCheckContext {

    /**
     * 获取外部符号注册表。
     *
     * @return 符号注册表
     */
    FlowDefinitionRegistry registry();

    /**
     * 递归检查子 Spec 节点。
     *
     * @param spec        子 Spec 节点
     * @param currentType 当前输入类型
     * @return 节点输出类型
     */
    TypeRef checkSpec(FlowSpec spec, TypeRef currentType);

    /**
     * 记录诊断信息。
     *
     * @param diagnostic 诊断项
     */
    void addDiagnostic(Diagnostic diagnostic);

    /**
     * 获取全部诊断信息列表。
     *
     * @return 诊断信息列表
     */
    List<Diagnostic> diagnostics();

    /**
     * 获取 Spec 节点输入类型映射表。
     *
     * @return Spec 输入类型 Map
     */
    Map<FlowSpec, TypeRef> specInputTypes();

    /**
     * 获取 Spec 节点输出类型映射表。
     *
     * @return Spec 输出类型 Map
     */
    Map<FlowSpec, TypeRef> specOutputTypes();

    /**
     * 检查指定流程 ID 是否正在当前递归调用链中（用于循环调用检测）。
     *
     * @param flowId 流程 ID
     * @return true 若该流程正处于访问链路中
     */
    boolean isVisiting(String flowId);

    /**
     * 将指定流程 ID 压入调用栈链路。
     *
     * @param flowId 流程 ID
     */
    void pushVisiting(String flowId);

    /**
     * 将指定流程 ID 从调用栈链路中弹出。
     *
     * @param flowId 流程 ID
     */
    void popVisiting(String flowId);

    /**
     * 获取当前调用链路中的全部流程 ID 集合（不可变视图）。
     *
     * @return 访问链路集合
     */
    Set<String> visitingFlows();
}
