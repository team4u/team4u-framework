package com.team4u.framework.criterion.compiler;

import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.util.PolicyScanner;

/**
 * 编译器注册表
 */
public class CompilerRegistry extends KeyedPolicyRegistry<Class<? extends Criterion>, CriterionCompiler<?>> {

    private static final CompilerRegistry GLOBAL = new CompilerRegistry();

    static {
        // 自动扫描当前包及其子包并注册
        PolicyScanner.scanAndRegister(GLOBAL);
        // 通过 ServiceLoader 加载
        PolicyScanner.registerFromServiceLoader(GLOBAL);
    }

    /**
     * 获取全局共享的编译器注册表实例
     *
     * @return 全局注册表实例
     */
    public static CompilerRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public CompilerRegistry() {
        super(CriterionCompiler.class);
    }
}
