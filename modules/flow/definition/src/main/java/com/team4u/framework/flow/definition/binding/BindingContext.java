package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.definition.model.FlowSpec;
import com.team4u.framework.flow.definition.model.SymbolRef;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;

import java.util.Map;

/**
 * 流程规范绑定上下文接口。
 *
 * @author jay.wu
 */
public interface BindingContext {

    /**
     * 获取符号注册表。
     *
     * @return 符号注册表
     */
    FlowDefinitionRegistry registry();

    /**
     * 递归绑定子 Spec 节点。
     *
     * @param spec 子 Spec 节点
     * @return 绑定的 Flow 实例
     */
    Flow<?, ?> bindSpec(FlowSpec spec);

    /**
     * 对 Flow 施加治理策略（支持 Provider 与静态 Descriptor，包含 Key 投影与配置解析）。
     *
     * @param flow          目标 Flow
     * @param policyId      策略 ID
     * @param keyRef        可选 Key 投影符号
     * @param configuration 配置字典
     * @return 增强后的 Flow 实例
     */
    Flow<?, ?> applyPolicy(
            Flow<?, ?> flow,
            String policyId,
            SymbolRef keyRef,
            Map<String, Object> configuration);
}
