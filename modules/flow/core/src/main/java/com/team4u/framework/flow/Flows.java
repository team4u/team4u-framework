package com.team4u.framework.flow;

/**
 * 流程工厂与流式构造入口。
 *
 * @author jay.wu
 */
public final class Flows {

    private Flows() {
    }

    /**
     * 开启一个新流程的流式定义。
     *
     * @param flowId 流程唯一标识，非 null
     * @param <I>    流程输入类型
     * @return 初始 FlowBuilder
     */
    public static <I> FlowBuilder<I, I> begin(String flowId) {
        return new FlowBuilder<>(flowId);
    }

    /**
     * 便捷工厂：创建单步转换 Flow。
     *
     * @param id           节点与流程 ID，非 null
     * @param step         业务步骤，非 null
     * @param interceptors 可选步骤拦截器
     * @param <I>          输入类型
     * @param <O>          输出类型
     * @return 单步 Flow 实例
     */
    public static <I, O> Flow<I, O> step(String id, Step<I, O> step, StepInterceptor... interceptors) {
        return Flows.<I>begin(id).step(id, step, interceptors).build();
    }

    /**
     * 便捷工厂：创建单步上下文型转换 Flow。
     *
     * @param id             节点与流程 ID，非 null
     * @param contextualStep 上下文型业务步骤，非 null
     * @param interceptors   可选步骤拦截器
     * @param <I>            输入类型
     * @param <O>            输出类型
     * @return 单步 Flow 实例
     */
    public static <I, O> Flow<I, O> step(String id, Step.Contextual<I, O> contextualStep, StepInterceptor... interceptors) {
        return Flows.<I>begin(id).step(id, contextualStep, interceptors).build();
    }

    /**
     * 便捷工厂：创建单步副作用透传 Flow。
     *
     * @param id           节点与流程 ID，非 null
     * @param action       副作用动作，非 null
     * @param interceptors 可选步骤拦截器
     * @param <I>          输入/输出类型
     * @return 单步 Flow 实例
     */
    public static <I> Flow<I, I> tap(String id, Action<I> action, StepInterceptor... interceptors) {
        return Flows.<I>begin(id).tap(id, action, interceptors).build();
    }

    /**
     * 便捷工厂：创建单步上下文型副作用透传 Flow。
     *
     * @param id               节点与流程 ID，非 null
     * @param contextualAction 上下文型副作用动作，非 null
     * @param interceptors     可选步骤拦截器
     * @param <I>              输入/输出类型
     * @return 单步 Flow 实例
     */
    public static <I> Flow<I, I> tap(String id, Action.Contextual<I> contextualAction, StepInterceptor... interceptors) {
        return Flows.<I>begin(id).tap(id, contextualAction, interceptors).build();
    }
}
