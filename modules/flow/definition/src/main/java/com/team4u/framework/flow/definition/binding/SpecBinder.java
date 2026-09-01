package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.definition.model.FlowSpec;
import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 流程规范绑定策略接口（Spec Binder）。
 *
 * @param <T> 流程规范 AST 类型
 * @author jay.wu
 */
public interface SpecBinder<T extends FlowSpec> extends KeyedPolicy<Class<? extends FlowSpec>> {

    /**
     * 将 Spec AST 绑定为强类型 Flow。
     *
     * @param spec    Spec 节点
     * @param context 绑定上下文
     * @return 绑定的 Flow 实例
     */
    Flow<?, ?> bind(T spec, BindingContext context);
}
