package com.team4u.framework.flow.test;

import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.StopReason;

import java.util.Objects;

/**
 * {@link FlowResult} 断言支持。
 *
 * @param <O> 成功产物类型
 * @author jay.wu
 */
public final class FlowResultAssert<O> {

    private final FlowResult<O> actual;

    FlowResultAssert(FlowResult<O> actual) {
        this.actual = Objects.requireNonNull(actual, "FlowResult must not be null");
    }

    public FlowResultAssert<O> isSucceeded() {
        if (!actual.isSucceeded()) {
            throw new AssertionError("Expected FlowResult to be SUCCEEDED but was " + actual.kind() +
                    (actual.isStopped() ? " with stopReason: " + actual.stopReason() : "") +
                    (actual.isFailed() ? " with failure: " + actual.failure() : ""));
        }
        return this;
    }

    public FlowResultAssert<O> hasValue(O expected) {
        isSucceeded();
        if (!Objects.equals(actual.value(), expected)) {
            throw new AssertionError("Expected value <" + expected + "> but was <" + actual.value() + ">");
        }
        return this;
    }

    public FlowResultAssert<O> isStopped() {
        if (!actual.isStopped()) {
            throw new AssertionError("Expected FlowResult to be STOPPED but was " + actual.kind() +
                    (actual.isSucceeded() ? " with value: " + actual.value() : "") +
                    (actual.isFailed() ? " with failure: " + actual.failure() : ""));
        }
        return this;
    }

    public FlowResultAssert<O> hasStopCode(String expectedCode) {
        isStopped();
        if (!Objects.equals(actual.stopReason().code(), expectedCode)) {
            throw new AssertionError("Expected StopReason code <" + expectedCode + "> but was <" + actual.stopReason().code() + ">");
        }
        return this;
    }

    public FlowResultAssert<O> hasStopReason(StopReason expectedReason) {
        isStopped();
        if (!Objects.equals(actual.stopReason(), expectedReason)) {
            throw new AssertionError("Expected StopReason <" + expectedReason + "> but was <" + actual.stopReason() + ">");
        }
        return this;
    }

    public FlowResultAssert<O> isFailed() {
        if (!actual.isFailed()) {
            throw new AssertionError("Expected FlowResult to be FAILED but was " + actual.kind() +
                    (actual.isSucceeded() ? " with value: " + actual.value() : "") +
                    (actual.isStopped() ? " with stopReason: " + actual.stopReason() : ""));
        }
        return this;
    }

    public FlowResultAssert<O> hasFailedNodeId(String expectedNodeId) {
        isFailed();
        if (!Objects.equals(actual.failure().nodeId(), expectedNodeId)) {
            throw new AssertionError("Expected failed nodeId <" + expectedNodeId + "> but was <" + actual.failure().nodeId() + ">");
        }
        return this;
    }

    public FlowResultAssert<O> hasFailedNodePath(String expectedNodePath) {
        isFailed();
        if (!Objects.equals(actual.failure().nodePath(), expectedNodePath)) {
            throw new AssertionError("Expected failed nodePath <" + expectedNodePath + "> but was <" + actual.failure().nodePath() + ">");
        }
        return this;
    }

    public FlowResultAssert<O> hasCauseInstanceOf(Class<? extends Throwable> expectedType) {
        isFailed();
        Throwable cause = actual.failure().cause();
        if (!expectedType.isInstance(cause)) {
            throw new AssertionError("Expected cause to be instance of " + expectedType.getName() +
                    " but was " + (cause != null ? cause.getClass().getName() : "null"));
        }
        return this;
    }

    public FlowResultAssert<O> hasCauseMessage(String expectedMessage) {
        isFailed();
        Throwable cause = actual.failure().cause();
        String msg = cause != null ? cause.getMessage() : null;
        if (!Objects.equals(msg, expectedMessage)) {
            throw new AssertionError("Expected cause message <" + expectedMessage + "> but was <" + msg + ">");
        }
        return this;
    }
}
