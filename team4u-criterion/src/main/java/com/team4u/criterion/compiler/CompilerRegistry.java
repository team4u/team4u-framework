package com.team4u.criterion.compiler;

import com.team4u.criterion.model.Criterion;
import com.team4u.policy.KeyedPolicyRegistry;

/**
 * 编译器注册表
 */
public class CompilerRegistry extends KeyedPolicyRegistry<Class<? extends Criterion>, CriterionCompiler<?>> {

    @SuppressWarnings("unchecked")
    public CompilerRegistry() {
        super((Class<CriterionCompiler<?>>) (Class<?>) CriterionCompiler.class);
    }
}
