package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowObserver;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.OperationResolver;
import com.team4u.framework.flow.ResumePoint;
import com.team4u.framework.flow.Suspension;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 本地流测试夹具（Local Flow Test Fixture）。
 *
 * <p>封装 {@link LocalExecutable} 的编译与运行过程，内置支持 {@link TraceCollector} 事件轨迹捕获与 {@link Suspension} 快捷挂起恢复验证。</p>
 *
 * @param <I> 流程输入类型
 * @param <O> 流程输出类型
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class LocalFixture<I, O> {

    private final LocalExecutable<I, O> executable;

    private LocalFixture(LocalExecutable<I, O> executable) {
        this.executable = Objects.requireNonNull(executable, "executable must not be null");
    }


    /** 默认 fixture：rejecting resolver + noop observer。 */
    public static <I, O> LocalFixture<I, O> compile(Flow<I, O> flow) {
        return new LocalFixture<I, O>(Local.compile(flow));
    }

    /** 注入 TraceCollector 的便捷工厂。 */
    public static <I, O> LocalFixture<I, O> compile(
            Flow<I, O> flow, final TraceCollector collector) {
        Objects.requireNonNull(collector, "collector must not be null");
        return new LocalFixture<I, O>(Local.compile(flow,
                OperationResolver.rejecting(), collector));
    }

    /** 全参工厂：显式 resolver 与 observer。 */
    public static <I, O> LocalFixture<I, O> compile(
            Flow<I, O> flow, OperationResolver resolver, FlowObserver observer) {
        return new LocalFixture<I, O>(Local.compile(flow, resolver, observer));
    }

    /** 直接包装已编译的 LocalExecutable。 */
    public static <I, O> LocalFixture<I, O> of(LocalExecutable<I, O> executable) {
        return new LocalFixture<I, O>(executable);
    }

    public FlowResult<O> run(I input) {
        return executable.run(input);
    }

    /** 要求结果为 Suspended，否则抛 AssertionError。返回续接句柄。 */
    public Suspension<O> requireSuspension(I input) {
        FlowResult<O> result = run(input);
        if (result instanceof FlowResult.Suspended) {
            return ((FlowResult.Suspended<O>) result).suspension();
        }
        throw new AssertionError("expected Local execution to suspend but was <"
                + result.getClass().getSimpleName() + ">");
    }

    /** 要求结果为 Completed/Accepted，返回输出值。 */
    public O requireAccepted(I input) {
        return run(input).requireAccepted();
    }

    /** 以指定信号恢复挂起的执行。 */
    public <R> FlowResult<O> resume(Suspension<O> suspension,
                                    ResumePoint<R> point, R signal) {
        return executable.resume(suspension, point, signal);
    }
}
