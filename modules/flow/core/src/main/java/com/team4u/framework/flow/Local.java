package com.team4u.framework.flow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * Local 内存执行器编译工厂门面。
 *
 * <p>负责将不可变 AST 逻辑流程定义（{@link Flow}）通过 {@link Compiler} 降级编译并静态验证为高效的内存可执行流 {@link LocalExecutable}。
 * 编译期会校验拓扑结构合法性（唯一 scope 名、唯一分支 token、唯一挂起点、禁止 parallel 内部非法 await 等），
 * 并使用提供的 {@link OperationResolver} 解析延迟绑定的组件。
 *
 * <p>线程池管理原则：
 * <ul>
 *   <li>若未显式指定 {@link ExecutorService}，默认借用 {@link ForkJoinPool#commonPool()} 进行并行分支及异步调用驱动；</li>
 *   <li>执行引擎仅“借用”线程池，绝不主动关闭外部传入的 {@link ExecutorService}。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class Local {
    private Local() { }

    /**
     * 编译逻辑流程（使用默认无 IoC 解析器、无操作观察者与 commonPool 线程池）。
     *
     * @param flow 逻辑流程定义，不能为 null
     * @param <I>  流程输入类型
     * @param <O>  流程输出类型
     * @return 编译就绪的 {@link LocalExecutable} 实例
     * @throws FlowBuildException 当流程定义存在拓扑冲突或非法节点时抛出
     */
    public static <I, O> LocalExecutable<I, O> compile(Flow<I, O> flow) {
        return compile(flow, OperationResolver.rejecting());
    }

    /**
     * 编译逻辑流程（注入自定义组件解析器）。
     *
     * @param flow     逻辑流程定义，不能为 null
     * @param resolver 组件解析器（如 Spring Bean 查找器），不能为 null
     * @param <I>      流程输入类型
     * @param <O>      流程输出类型
     * @return 编译就绪的 {@link LocalExecutable} 实例
     * @throws FlowBuildException 当流程定义存在拓扑冲突或无法解析的组件时抛出
     */
    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, OperationResolver resolver) {
        return compile(flow, resolver, FlowObserver.noop());
    }

    /**
     * 编译逻辑流程（注入自定义并发线程池）。
     *
     * @param flow     逻辑流程定义，不能为 null
     * @param executor 用于并发/异步调度的线程池，为 null 时默认回退到 commonPool
     * @param <I>      流程输入类型
     * @param <O>      流程输出类型
     * @return 编译就绪的 {@link LocalExecutable} 实例
     * @throws FlowBuildException 当流程定义存在拓扑冲突时抛出
     */
    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, ExecutorService executor) {
        return compile(flow, OperationResolver.rejecting(), FlowObserver.noop(), executor);
    }

    /**
     * 编译逻辑流程（注入组件解析器与事件观察者）。
     *
     * @param flow     逻辑流程定义，不能为 null
     * @param resolver 组件解析器，不能为 null
     * @param observer 事件观察者，不能为 null
     * @param <I>      流程输入类型
     * @param <O>      流程输出类型
     * @return 编译就绪的 {@link LocalExecutable} 实例
     * @throws FlowBuildException 当流程定义校验失败时抛出
     */
    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, OperationResolver resolver, FlowObserver observer) {
        return compile(flow, resolver, observer, ForkJoinPool.commonPool());
    }

    /**
     * 全参编译逻辑流程为内存可执行实例。
     *
     * @param flow     逻辑流程定义，不能为 null
     * @param resolver 组件解析器，不能为 null
     * @param observer 事件观察者（为 null 则自动使用 noop 观察者）
     * @param executor 并发执行线程池（为 null 则自动使用 ForkJoinPool.commonPool）
     * @param <I>      流程输入类型
     * @param <O>      流程输出类型
     * @return 编译就绪的 {@link LocalExecutable} 实例
     * @throws FlowBuildException 当流程定义存在拓扑错误、命名冲突或组件未解析时抛出
     */
    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, OperationResolver resolver, FlowObserver observer, ExecutorService executor) {
        return new LocalExecutable<I, O>(Compiler.compile(flow, resolver),
                observer, executor != null ? executor : ForkJoinPool.commonPool());
    }
}

