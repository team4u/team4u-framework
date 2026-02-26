package com.team4u.framework.criterion.compiler;

import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 编译器注册表
 */
public class CompilerRegistry extends KeyedPolicyRegistry<Class<? extends Criterion>, CriterionCompiler<?>> {

    @SuppressWarnings("unchecked")
    public CompilerRegistry() {
        super(CriterionCompiler.class);
    }
}
