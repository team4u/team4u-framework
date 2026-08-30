package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 流程三态执行结果：SUCCEEDED、STOPPED 或 FAILED。
 *
 * @param <O> 成功产物类型
 * @author jay.wu
 */
public final class FlowResult<O> {

    public enum Kind {
        SUCCEEDED,
        STOPPED,
        FAILED
    }

    private final Kind kind;
    private final O value;
    private final StopReason stopReason;
    private final FailureContext failure;

    private FlowResult(Kind kind, O value, StopReason stopReason, FailureContext failure) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.value = value;
        this.stopReason = stopReason;
        this.failure = failure;
    }

    public static <O> FlowResult<O> succeeded(O value) {
        if (value == null) {
            throw new IllegalArgumentException("Flow succeeded value must not be null");
        }
        return new FlowResult<>(Kind.SUCCEEDED, value, null, null);
    }

    public static <O> FlowResult<O> stopped(StopReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("StopReason must not be null");
        }
        return new FlowResult<>(Kind.STOPPED, null, reason, null);
    }

    public static <O> FlowResult<O> failed(String nodeId, Throwable cause) {
        return failed(nodeId, nodeId, cause);
    }

    public static <O> FlowResult<O> failed(String nodeId, String nodePath, Throwable cause) {
        return new FlowResult<>(Kind.FAILED, null, null, new FailureContext(nodeId, nodePath, cause));
    }

    public static <O> FlowResult<O> failed(FailureContext failure) {
        if (failure == null) {
            throw new IllegalArgumentException("FailureContext must not be null");
        }
        return new FlowResult<>(Kind.FAILED, null, null, failure);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isSucceeded() {
        return kind == Kind.SUCCEEDED;
    }

    public boolean isStopped() {
        return kind == Kind.STOPPED;
    }

    public boolean isFailed() {
        return kind == Kind.FAILED;
    }

    public O value() {
        if (kind != Kind.SUCCEEDED) {
            throw new IllegalStateException("FlowResult is not SUCCEEDED (actual kind: " + kind + ")");
        }
        return value;
    }

    public StopReason stopReason() {
        if (kind != Kind.STOPPED) {
            throw new IllegalStateException("FlowResult is not STOPPED (actual kind: " + kind + ")");
        }
        return stopReason;
    }

    public FailureContext failure() {
        if (kind != Kind.FAILED) {
            throw new IllegalStateException("FlowResult is not FAILED (actual kind: " + kind + ")");
        }
        return failure;
    }

    public Throwable cause() {
        return failure().cause();
    }

    public Throwable error() {
        return cause();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FlowResult)) return false;
        FlowResult<?> that = (FlowResult<?>) o;
        return kind == that.kind &&
                Objects.equals(value, that.value) &&
                Objects.equals(stopReason, that.stopReason) &&
                Objects.equals(failure, that.failure);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, value, stopReason, failure);
    }

    @Override
    public String toString() {
        switch (kind) {
            case SUCCEEDED:
                return "SUCCEEDED(" + value + ")";
            case STOPPED:
                return "STOPPED(" + stopReason + ")";
            case FAILED:
                return "FAILED(" + failure.nodeId() + ": " + failure.cause().getMessage() + ")";
            default:
                return "FlowResult{" + kind + "}";
        }
    }
}
