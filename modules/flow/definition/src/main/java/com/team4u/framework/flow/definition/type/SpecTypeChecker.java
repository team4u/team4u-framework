package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.definition.model.FlowSpec;
import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 流程规范静态类型检查策略接口。
 *
 * @param <T> 流程规范 AST 类型
 * @author jay.wu
 */
public interface SpecTypeChecker<T extends FlowSpec> extends KeyedPolicy<Class<? extends FlowSpec>> {

    /**
     * 校验当前 Spec 节点并推导输出类型。
     *
     * @param spec        待检查的 Spec 节点
     * @param currentType 当前输入类型
     * @param context     类型检查上下文
     * @return 节点输出类型
     */
    TypeRef check(T spec, TypeRef currentType, TypeCheckContext context);
}
