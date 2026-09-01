package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.model.FlowSpec;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;

import java.util.List;
import java.util.Map;

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
}
