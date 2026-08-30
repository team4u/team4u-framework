package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.StopReason;

import java.util.Objects;

/**
 * Durable 持久化执行结果模型。
 *
 * @param <O> 成功产物类型
 * @author jay.wu
 */
public final class DurableResult<O> {

    private final String flowId;
    private final int flowVersion;
    private final String executionId;
    private final DurableLifecycle lifecycle;
    private final long revision;
    private final O value;
    private final StopReason stopReason;
    private final DurableFailure failure;

    private DurableResult(String flowId, int flowVersion, String executionId,
                          DurableLifecycle lifecycle, long revision,
                          O value, StopReason stopReason, DurableFailure failure) {
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.flowVersion = flowVersion;
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.revision = revision;
        this.value = value;
        this.stopReason = stopReason;
        this.failure = failure;
    }

    public static <O> DurableResult<O> completed(String flowId, int flowVersion, String executionId, long revision, O value) {
        return new DurableResult<>(flowId, flowVersion, executionId, DurableLifecycle.COMPLETED, revision, value, null, null);
    }

    public static <O> DurableResult<O> stopped(String flowId, int flowVersion, String executionId, long revision, StopReason reason) {
        return new DurableResult<>(flowId, flowVersion, executionId, DurableLifecycle.STOPPED, revision, null, reason, null);
    }

    public static <O> DurableResult<O> failed(String flowId, int flowVersion, String executionId, long revision, DurableFailure failure) {
        return new DurableResult<>(flowId, flowVersion, executionId, DurableLifecycle.FAILED, revision, null, null, failure);
    }

    public static <O> DurableResult<O> cancelled(String flowId, int flowVersion, String executionId, long revision) {
        return new DurableResult<>(flowId, flowVersion, executionId, DurableLifecycle.CANCELLED, revision, null, null, null);
    }

    public static <O> DurableResult<O> active(String flowId, int flowVersion, String executionId, long revision) {
        return new DurableResult<>(flowId, flowVersion, executionId, DurableLifecycle.ACTIVE, revision, null, null, null);
    }

    public String flowId() { return flowId; }
    public int flowVersion() { return flowVersion; }
    public String executionId() { return executionId; }
    public DurableLifecycle lifecycle() { return lifecycle; }
    public long revision() { return revision; }

    public boolean isCompleted() { return lifecycle == DurableLifecycle.COMPLETED; }
    public boolean isStopped() { return lifecycle == DurableLifecycle.STOPPED; }
    public boolean isFailed() { return lifecycle == DurableLifecycle.FAILED; }
    public boolean isCancelled() { return lifecycle == DurableLifecycle.CANCELLED; }
    public boolean isActive() { return lifecycle == DurableLifecycle.ACTIVE; }

    public O value() {
        if (!isCompleted()) {
            throw new IllegalStateException("Result is not COMPLETED (actual: " + lifecycle + ")");
        }
        return value;
    }

    public StopReason stopReason() {
        if (!isStopped()) {
            throw new IllegalStateException("Result is not STOPPED (actual: " + lifecycle + ")");
        }
        return stopReason;
    }

    public DurableFailure failure() {
        if (!isFailed()) {
            throw new IllegalStateException("Result is not FAILED (actual: " + lifecycle + ")");
        }
        return failure;
    }

    @Override
    public String toString() {
        return "DurableResult{" + flowId + "(v" + flowVersion + ") " + executionId + "=" + lifecycle +
                (isCompleted() ? ", value=" + value : "") +
                (isStopped() ? ", stop=" + stopReason : "") +
                (isFailed() ? ", failure=" + failure : "") +
                ", rev=" + revision + '}';
    }
}
