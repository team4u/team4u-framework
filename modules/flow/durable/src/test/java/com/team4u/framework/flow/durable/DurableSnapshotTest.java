package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.StopReason;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link DurableSnapshot} 单元测试：
 * 验证快照元数据校验（非空、非负、正版本号、无隐式归一化）以及生命周期/载荷不变式。
 *
 * @author jay.wu
 */
public class DurableSnapshotTest {

    @Test
    public void testValidSnapshotCreation() {
        Map<String, StoredValue> slots = new HashMap<>();
        slots.put("input", StoredValue.ofString("hello"));

        DurableSnapshot snap = new DurableSnapshot(
                "flow-1", 1, "exec-1",
                DurableSnapshot.DEFAULT_FORMAT_ID, DurableSnapshot.DEFAULT_FORMAT_VERSION,
                1L, DurableLifecycle.ACTIVE, FrameState.initial(),
                slots, null, null
        );

        Assert.assertEquals("flow-1", snap.flowId());
        Assert.assertEquals(1, snap.flowVersion());
        Assert.assertEquals("exec-1", snap.executionId());
        Assert.assertEquals(DurableSnapshot.DEFAULT_FORMAT_ID, snap.formatId());
        Assert.assertEquals(DurableSnapshot.DEFAULT_FORMAT_VERSION, snap.formatVersion());
        Assert.assertEquals(1L, snap.revision());
        Assert.assertEquals(DurableLifecycle.ACTIVE, snap.lifecycle());
        Assert.assertNotNull(snap.frameState());
        Assert.assertEquals(1, snap.slots().size());
        Assert.assertNull(snap.stopReason());
        Assert.assertNull(snap.failure());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullFlowIdRejected() {
        new DurableSnapshot(null, 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBlankFlowIdRejected() {
        new DurableSnapshot("  ", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroFlowVersionRejected() {
        new DurableSnapshot("flow-1", 0, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeFlowVersionRejected() {
        new DurableSnapshot("flow-1", -1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullExecutionIdRejected() {
        new DurableSnapshot("flow-1", 1, null, DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBlankExecutionIdRejected() {
        new DurableSnapshot("flow-1", 1, "  ", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullFormatIdRejectedWithoutNormalization() {
        new DurableSnapshot("flow-1", 1, "exec-1", null, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBlankFormatIdRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", "  ", 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroFormatVersionRejectedWithoutNormalization() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 0, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeFormatVersionRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, -1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeRevisionRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, -1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = NullPointerException.class)
    public void testNullLifecycleRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                null, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = NullPointerException.class)
    public void testNullFrameStateRejectedWithoutNormalization() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, null, Collections.emptyMap(), null, null);
    }

    @Test(expected = NullPointerException.class)
    public void testNullSlotsRejectedWithoutNormalization() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), null, null, null);
    }

    // Lifecycle invariant tests

    @Test(expected = IllegalArgumentException.class)
    public void testActiveWithStopReasonRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), StopReason.of("STOP"), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testActiveWithFailureRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(), Collections.emptyMap(), null,
                new DurableFailure("n1", "n1", "Ex", "msg"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCompletedWithStopReasonRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.COMPLETED, FrameState.initial(), Collections.emptyMap(), StopReason.of("STOP"), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCompletedWithFailureRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.COMPLETED, FrameState.initial(), Collections.emptyMap(), null,
                new DurableFailure("n1", "n1", "Ex", "msg"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testStoppedWithoutStopReasonRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.STOPPED, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testStoppedWithFailureRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.STOPPED, FrameState.initial(), Collections.emptyMap(), StopReason.of("STOP"),
                new DurableFailure("n1", "n1", "Ex", "msg"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFailedWithoutFailureRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.FAILED, FrameState.initial(), Collections.emptyMap(), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFailedWithStopReasonRejected() {
        new DurableSnapshot("flow-1", 1, "exec-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.FAILED, FrameState.initial(), Collections.emptyMap(), StopReason.of("STOP"),
                new DurableFailure("n1", "n1", "Ex", "msg"));
    }

    @Test
    public void testWithMethodsProduceValidSnapshots() {
        DurableSnapshot init = DurableSnapshot.initial("flow-1", 1, "exec-1", StoredValue.ofString("inputVal"));
        Assert.assertEquals(1L, init.revision());
        Assert.assertEquals(DurableLifecycle.ACTIVE, init.lifecycle());

        DurableSnapshot withSlot = init.withSlot("active", StoredValue.ofString("activeVal"));
        Assert.assertEquals(1L, withSlot.revision());
        Assert.assertEquals(StoredValue.ofString("activeVal"), withSlot.getSlot("active"));

        DurableSnapshot checkpoint = withSlot.withCheckpoint(1, "active", StoredValue.ofString("nextVal"));
        Assert.assertEquals(2L, checkpoint.revision());
        Assert.assertEquals(1, checkpoint.frameState().cursor());

        DurableSnapshot stopped = checkpoint.withStopped(StopReason.of("CUSTOM_STOP"));
        Assert.assertEquals(3L, stopped.revision());
        Assert.assertEquals(DurableLifecycle.STOPPED, stopped.lifecycle());
        Assert.assertEquals("CUSTOM_STOP", stopped.stopReason().code());

        DurableSnapshot failed = checkpoint.withFailed(new DurableFailure("step-1", "path-1", "ExClass", "error msg"));
        Assert.assertEquals(3L, failed.revision());
        Assert.assertEquals(DurableLifecycle.FAILED, failed.lifecycle());
        Assert.assertEquals("step-1", failed.failure().nodeId());

        DurableSnapshot retried = failed.withRetryActive();
        Assert.assertEquals(4L, retried.revision());
        Assert.assertEquals(DurableLifecycle.ACTIVE, retried.lifecycle());
        Assert.assertNull(retried.failure());

        DurableSnapshot completed = retried.withCompleted(StoredValue.ofString("out"));
        Assert.assertEquals(5L, completed.revision());
        Assert.assertEquals(DurableLifecycle.COMPLETED, completed.lifecycle());
        Assert.assertEquals(StoredValue.ofString("out"), completed.getSlot("output"));

        DurableSnapshot cancelled = failed.withCancelled();
        Assert.assertEquals(4L, cancelled.revision());
        Assert.assertEquals(DurableLifecycle.CANCELLED, cancelled.lifecycle());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBodyFrameCarryingPendingFailureRejected() {
        new FrameState.ExecutionFrame("/", 0, "input", null,
                FrameState.Phase.BODY, new DurableFailure("n1", "n1", "Ex", "msg"), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBodyFrameCarryingPendingStopReasonRejected() {
        new FrameState.ExecutionFrame("/", 0, "input", null,
                FrameState.Phase.BODY, null, StopReason.of("STOP"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRecoverFrameWithoutPendingFailureRejected() {
        new FrameState.ExecutionFrame("/", 0, "input", null,
                FrameState.Phase.RECOVER, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRecoverFrameCarryingPendingStopReasonRejected() {
        new FrameState.ExecutionFrame("/", 0, "input", null,
                FrameState.Phase.RECOVER, new DurableFailure("n1", "n1", "Ex", "msg"), StopReason.of("STOP"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEnsureFrameCarryingBothFailureAndStopRejected() {
        new FrameState.ExecutionFrame("/", 0, "input", null,
                FrameState.Phase.ENSURE, new DurableFailure("n1", "n1", "Ex", "msg"), StopReason.of("STOP"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCompletedFrameCarryingPendingFailureRejected() {
        new FrameState.ExecutionFrame("/", 0, "input", null,
                FrameState.Phase.COMPLETED, new DurableFailure("n1", "n1", "Ex", "msg"), null);
    }

    @Test
    public void testRetryFrameStatePreservedAndRestoredOnRetry() {
        FrameState rootFrame = FrameState.initial();
        FrameState retryCheckpoint = rootFrame.pushFrame(
                new FrameState.ExecutionFrame("/sub", 1, "input:/sub", null, FrameState.Phase.BODY, null, null, "sub"));

        DurableSnapshot init = DurableSnapshot.initial("flow-1", 1, "exec-1", StoredValue.ofString("in"));
        DurableSnapshot withRetry = init.withRetryFrameState(retryCheckpoint);
        Assert.assertEquals(retryCheckpoint, withRetry.retryFrameState());

        DurableSnapshot failed = withRetry.withFailed(new DurableFailure("step-2", "sub/step-2", "Ex", "failed"));
        Assert.assertEquals(DurableLifecycle.FAILED, failed.lifecycle());
        Assert.assertEquals(retryCheckpoint, failed.retryFrameState());

        // withRetryActive restores the retryFrameState as active frameState, and resets retryFrameState to null
        DurableSnapshot retried = failed.withRetryActive();
        Assert.assertEquals(DurableLifecycle.ACTIVE, retried.lifecycle());
        Assert.assertEquals(retryCheckpoint, retried.frameState());
        Assert.assertNull(retried.retryFrameState());
    }
}
