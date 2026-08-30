package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 具有特定版本的持久化流程实例（不可变，线程安全）。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author jay.wu
 */
public final class DurableFlow<I, O> {

    private final String flowId;
    private final int flowVersion;
    private final Flow<I, O> flowDefinition;
    private final DurablePlanNode plan;
    private final DurableStore store;
    private final StateMapper stateMapper;
    private final DurableRunner runner;

    DurableFlow(String flowId, int flowVersion, Flow<I, O> flowDefinition,
                DurableStore store, StateMapper stateMapper) {
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.flowVersion = flowVersion;
        this.flowDefinition = Objects.requireNonNull(flowDefinition, "flowDefinition must not be null");
        this.store = Objects.requireNonNull(store, "DurableStore must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "StateMapper must not be null");
        this.runner = new DurableRunner(store, stateMapper);
        this.plan = flowDefinition.project(DurablePlanCompiler.INSTANCE);
    }

    public String flowId() {
        return flowId;
    }

    public int flowVersion() {
        return flowVersion;
    }

    public Flow<I, O> definition() {
        return flowDefinition;
    }

    /**
     * 开启新流程执行：落初始快照后开始执行。
     *
     * @param executionId 执行唯一标识，非 null
     * @param input       流程入参，非 null
     * @return 执行结果
     */
    public DurableResult<O> start(String executionId, I input) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(input, "input must not be null");

        DurableSnapshot existing = store.load(flowId, executionId);
        if (existing != null) {
            throw new IllegalStateException("Execution [" + executionId + "] already exists in flow [" + flowId + "]");
        }

        StoredValue inputSlot;
        try {
            inputSlot = stateMapper.encode(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to encode input for execution [" + executionId + "]", e);
        }

        DurableSnapshot initial = DurableSnapshot.initial(flowId, flowVersion, executionId, inputSlot);
        boolean saved = store.save(initial, 0L);
        if (!saved) {
            throw new IllegalStateException("CAS conflict on creating execution [" + executionId + "]");
        }

        return runner.run(plan, initial);
    }

    /**
     * 恢复执行处于 ACTIVE 状态的流程（例如进程崩溃重启后）。
     *
     * @param executionId 执行唯一标识，非 null
     * @return 执行结果
     */
    public DurableResult<O> recover(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        DurableSnapshot snapshot = store.load(flowId, executionId);
        if (snapshot == null) {
            throw new NoSuchElementException("Execution [" + executionId + "] not found in flow [" + flowId + "]");
        }
        if (snapshot.flowVersion() != this.flowVersion) {
            throw new IllegalStateException("Snapshot version [" + snapshot.flowVersion() +
                    "] does not match registered flow version [" + this.flowVersion + "]");
        }
        if (snapshot.lifecycle() != DurableLifecycle.ACTIVE) {
            return load(executionId);
        }
        return runner.run(plan, snapshot);
    }

    /**
     * 重试处于 FAILED 状态的执行：CAS 转回 ACTIVE 并从最后成功快照重放失败节点。
     *
     * @param executionId 执行唯一标识，非 null
     * @return 执行结果
     */
    public DurableResult<O> retry(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        DurableSnapshot snapshot = store.load(flowId, executionId);
        if (snapshot == null) {
            throw new NoSuchElementException("Execution [" + executionId + "] not found in flow [" + flowId + "]");
        }
        if (snapshot.lifecycle() != DurableLifecycle.FAILED) {
            throw new IllegalStateException("Cannot retry execution [" + executionId +
                    "] with lifecycle [" + snapshot.lifecycle() + "] (expected FAILED)");
        }

        DurableSnapshot activeSnap = snapshot.withRetryActive();
        boolean saved = store.save(activeSnap, snapshot.revision());
        if (!saved) {
            throw new IllegalStateException("CAS conflict when retrying execution [" + executionId + "]");
        }

        return runner.run(plan, activeSnap);
    }

    /**
     * 取消流程执行（支持 ACTIVE 或 FAILED 状态）。
     *
     * @param executionId 执行唯一标识，非 null
     * @return true 表示成功取消，false 表示已处于完成/停止状态或并发冲突
     */
    public boolean cancel(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        DurableSnapshot snapshot = store.load(flowId, executionId);
        if (snapshot == null) {
            return false;
        }
        if (snapshot.lifecycle() == DurableLifecycle.COMPLETED ||
                snapshot.lifecycle() == DurableLifecycle.STOPPED ||
                snapshot.lifecycle() == DurableLifecycle.CANCELLED) {
            return false;
        }

        DurableSnapshot cancelSnap = snapshot.withCancelled();
        return store.save(cancelSnap, snapshot.revision());
    }

    /**
     * 查询当前快照执行结果。
     *
     * @param executionId 执行唯一标识，非 null
     * @return 执行结果
     */
    public DurableResult<O> load(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        DurableSnapshot snapshot = store.load(flowId, executionId);
        if (snapshot == null) {
            throw new NoSuchElementException("Execution [" + executionId + "] not found in flow [" + flowId + "]");
        }
        return runner.run(plan, snapshot);
    }
}
