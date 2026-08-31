package com.team4u.framework.flow.bean;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;

/**
 * Spring / Bean 容器流编译门面工具类（Bean-Backed Flow Compilation Facade）。
 *
 * <p>提供基于 {@link BeanManager} 容器的便捷静态编译入口，将流程 DSL 中声明的 Operation/Policy 绑定为 IoC 容器中的单例 Bean。</p>
 *
 * @author jay.wu
 */
public final class BeanFlows {
    private BeanFlows() { }

    /**
     * 使用全局默认 {@link BeanManager#getInstance()} 编译内存流。
     *
     * @param flow 逻辑流定义，不能为 null
     * @param <I>  流程输入类型
     * @param <O>  流程输出类型
     * @return 编译后的本地可执行句柄
     */
    public static <I, O> LocalExecutable<I, O> compile(Flow<I, O> flow) {
        return compile(flow, BeanManager.getInstance());
    }

    /**
     * 使用指定的 {@link BeanManager} 容器实例编译内存流。
     *
     * @param flow        逻辑流定义，不能为 null
     * @param beanManager Bean 管理器实例，不能为 null
     * @param <I>         流程输入类型
     * @param <O>         流程输出类型
     * @return 编译后的本地可执行句柄
     */
    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, BeanManager beanManager) {
        return Local.compile(flow, new BeanOperationResolver(beanManager));
    }
}

