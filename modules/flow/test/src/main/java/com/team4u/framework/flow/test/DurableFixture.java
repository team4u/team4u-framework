package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.spi.OperationResolver;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.durable.DurableExecutable;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.DurableRuntime;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.Optional;

/**
 * 耐久化流测试夹具（Durable Flow Test Fixture）。
 *
 * <p>默认使用 {@link InMemoryDurableStore} 与 {@link DurableRuntime} 编译 {@link DurableExecutable}，
 * 为单元测试提供 start / recover / resume / cancel / snapshot 的精简操作门面与断言能力。</p>
 *
 * @param <I> 流程输入类型
 * @param <O> 流程输出类型
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class DurableFixture<I, O> {

    private final DurableExecutable<I, O> executable;

    private DurableFixture(DurableExecutable<I, O> executable) {
        this.executable = Objects.requireNonNull(executable, "executable must not be null");
    }


    /** 默认 fixture：InMemoryDurableStore + rejecting resolver。 */
    public static <I, O> DurableFixture<I, O> compile(
            Flow<I, O> flow, String flowId, int flowVersion) {
        return withStore(new InMemoryDurableStore(), flow, flowId, flowVersion);
    }

    /** 指定 store（如注入冲突/崩溃的探针）构建 runtime 并编译。 */
    public static <I, O> DurableFixture<I, O> withStore(
            DurableStore store, Flow<I, O> flow, String flowId, int flowVersion) {
        return withRuntime(DurableRuntime.builder(store).build(), flow, flowId, flowVersion);
    }

    /** 指定 runtime（可自定义 stateMapper/observer）编译。 */
    public static <I, O> DurableFixture<I, O> withRuntime(
            DurableRuntime runtime, Flow<I, O> flow, String flowId, int flowVersion) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        return new DurableFixture<I, O>(runtime.compile(flow, flowId, flowVersion));
    }

    /** 直接包装已编译的 DurableExecutable。 */
    public static <I, O> DurableFixture<I, O> of(DurableExecutable<I, O> executable) {
        return new DurableFixture<I, O>(executable);
    }

    /** 开启新执行；重复 start 以 EXECUTION_EXISTS 拒绝。崩溃即重抛。 */
    public DurableResult<O> start(String executionId, I input) {
        return executable.start(executionId, input);
    }

    /** 恢复 ACTIVE 执行（从最后提交的快照继续）；非 ACTIVE 以 LIFECYCLE_MISMATCH 拒绝。 */
    public DurableResult<O> recover(String executionId) {
        return executable.recover(executionId);
    }

    /** 向 SUSPENDED 执行注入信号并驱动续接。 */
    public <R> DurableResult<O> resume(String executionId,
                                       ResumePoint<R> point, R signal) {
        return executable.resume(executionId, point.name(), signal);
    }

    /** 取消 ACTIVE/SUSPENDED 执行。 */
    public DurableResult<O> cancel(String executionId) {
        return executable.cancel(executionId);
    }

    /** 读取快照（无副作用）。 */
    public Optional<DurableSnapshot> snapshot(String executionId) {
        return executable.snapshot(executionId);
    }

    /** 要求快照存在，否则抛 AssertionError。 */
    public DurableSnapshot requireSnapshot(String executionId) {
        Optional<DurableSnapshot> found = snapshot(executionId);
        if (!found.isPresent()) {
            throw new AssertionError("missing durable execution: " + executionId);
        }
        return found.get();
    }
}
