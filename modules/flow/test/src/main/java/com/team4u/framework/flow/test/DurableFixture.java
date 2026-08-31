package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.OperationResolver;
import com.team4u.framework.flow.ResumePoint;
import com.team4u.framework.flow.durable.DurableExecutable;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.DurableRuntime;
import com.team4u.framework.flow.durable.DurableSnapshot;
import com.team4u.framework.flow.durable.DurableStore;
import com.team4u.framework.flow.durable.InMemoryDurableStore;

import java.util.Objects;
import java.util.Optional;

/**
 * Durable 执行的精简类型化 facade：默认以 {@link InMemoryDurableStore} 构建
 * {@link DurableRuntime} 并编译指定 flowId/version 的 {@link DurableExecutable}，
 * 委托 start/recover/resume/cancel/snapshot 命令。
 *
 * <p><b>崩溃即重抛</b>：Durable 命令遵循 load → 校验 → 变更 → CAS 提交 → 驱动 的顺序。
 * 若进程在 CAS 提交后、驱动完成前崩溃（或注入的 store 在提交时抛错），命令会直接
 * 向调用方重抛原异常（如 {@code DurableException} 的 STORE_FAILURE/REVISION_CONFLICT，
 * 或自定义 Error）；由于快照已落库，后续以同一 flow 重新 compile 并调用
 * {@link #recover(String)} 即可从最后提交的检查点继续。本 fixture 不吞异常、
 * 不自动重试——测试需要模拟崩溃时直接捕获重抛的异常即可。</p>
 */
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

    public DurableExecutable<I, O> executable() {
        return executable;
    }
}
