package com.team4u.framework.criterion;

import com.team4u.framework.criterion.compiler.CompilerRegistry;
import com.team4u.framework.criterion.model.convert.ValueConverterRegistry;
import com.team4u.framework.policy.PolicyRegistry;
import com.team4u.framework.policy.PolicyScanner;

/**
 * 规则引擎标准默认策略持有者
 * <p>
 * 负责在类加载时执行一次性的策略扫描和加载，作为所有实例的原型基础。
 *
 * @author jay.wu
 */
class StandardDefaults {

    /**
     * 全局标准编译器注册表
     */
    static final CompilerRegistry GLOBAL_COMPILERS = new CompilerRegistry();

    /**
     * 全局标准转换器注册表
     */
    static final ValueConverterRegistry GLOBAL_CONVERTERS = new ValueConverterRegistry();

    static {
        // 在类加载时执行一次全量扫描和加载
        init(GLOBAL_COMPILERS);
        init(GLOBAL_CONVERTERS);
    }

    private static <P> void init(PolicyRegistry<P> registry) {
        // 1. 自动扫描当前包及其子包
        PolicyScanner.scanAndRegister(registry);
        // 2. 通过 ServiceLoader 加载
        PolicyScanner.registerFromServiceLoader(registry);
    }
}
