package com.team4u.framework.flow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * Local 投影入口：将不可变的逻辑 {@link Flow} 编译为可同步/异步执行的 {@link LocalExecutable}。
 */
public final class Local {
    private Local() { }

    public static <I, O> LocalExecutable<I, O> compile(Flow<I, O> flow) {
        return compile(flow, OperationResolver.rejecting());
    }

    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, OperationResolver resolver) {
        return compile(flow, resolver, FlowObserver.noop());
    }

    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, ExecutorService executor) {
        return compile(flow, OperationResolver.rejecting(), FlowObserver.noop(), executor);
    }

    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, OperationResolver resolver, FlowObserver observer) {
        return compile(flow, resolver, observer, ForkJoinPool.commonPool());
    }

    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, OperationResolver resolver, FlowObserver observer, ExecutorService executor) {
        return new LocalExecutable<I, O>(Compiler.compile(flow, resolver),
                observer, executor != null ? executor : ForkJoinPool.commonPool());
    }
}
