package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Flows;
import com.team4u.framework.flow.Step;
import com.team4u.framework.flow.StopReason;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link DurableFlow} 边界契约与集中式校验测试：
 * 验证 (1) load 为纯只读/解码且绝不执行 ACTIVE 节点；
 * (2) recover/retry/cancel/load 集中式校验（版本强隔离、元数据校验、生命周期不变式）。
 *
 * @author jay.wu
 */
public class DurableFlowBoundaryValidationTest {

    private InMemoryDurableStore store;
    private DurableRuntime runtime;

    @Before
    public void setUp() {
        store = new InMemoryDurableStore();
        runtime = DurableRuntime.builder(store).build();
    }

    // ==========================================
    // 1. load 是纯读/解码，绝不执行 ACTIVE 节点
    // ==========================================

    @Test
    public void loadOnActiveExecution_isPureRead_neverRunsActiveNodes() {
        AtomicInteger stepExecuted = new AtomicInteger(0);

        Flow<String, String> flow = Flows.<String>begin("pure-load-flow")
                .step("step-active", in -> {
                    stepExecuted.incrementAndGet();
                    return in + "-done";
                })
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);

        // Pre-save ACTIVE snapshot at cursor 0
        DurableSnapshot initSnap = DurableSnapshot.initial("pure-load-flow", 1, "exec-load-1", StoredValue.ofString("inputVal"));
        store.save(initSnap, 0L);

        // Call load() on ACTIVE execution
        DurableResult<String> loadResult = durableFlow.load("exec-load-1");

        // Verify result is ACTIVE and not completed
        Assert.assertTrue(loadResult.isActive());
        Assert.assertFalse(loadResult.isCompleted());
        Assert.assertEquals("pure-load-flow", loadResult.flowId());
        Assert.assertEquals(1, loadResult.flowVersion());
        Assert.assertEquals("exec-load-1", loadResult.executionId());
        Assert.assertEquals(1L, loadResult.revision());

        // Verify step node was NOT executed
        Assert.assertEquals(0, stepExecuted.get());

        // Verify snapshot in store was not modified
        DurableSnapshot storeSnap = store.load("pure-load-flow", "exec-load-1");
        Assert.assertEquals(DurableLifecycle.ACTIVE, storeSnap.lifecycle());
        Assert.assertEquals(1L, storeSnap.revision());
        Assert.assertEquals(0, storeSnap.frameState().cursor());

        // Now recover() should actually execute the step
        DurableResult<String> recResult = durableFlow.recover("exec-load-1");
        Assert.assertTrue(recResult.isCompleted());
        Assert.assertEquals("inputVal-done", recResult.value());
        Assert.assertEquals(1, stepExecuted.get());
    }

    @Test
    public void loadOnCompletedExecution_decodesValue() {
        Flow<String, String> flow = Flows.<String>begin("load-comp-flow")
                .step("s1", in -> in + "-ok")
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        DurableResult<String> startRes = durableFlow.start("exec-comp-1", "start");
        Assert.assertTrue(startRes.isCompleted());

        // Now load
        DurableResult<String> loadRes = durableFlow.load("exec-comp-1");
        Assert.assertTrue(loadRes.isCompleted());
        Assert.assertEquals("start-ok", loadRes.value());
        Assert.assertEquals(startRes.revision(), loadRes.revision());
    }

    @Test
    public void loadOnStoppedExecution_decodesStopReason() {
        Flow<String, String> flow = Flows.<String>begin("load-stop-flow")
                .guard("g1", in -> false, in -> StopReason.of("REJECTED", "Too low"))
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        DurableResult<String> startRes = durableFlow.start("exec-stop-1", "start");
        Assert.assertTrue(startRes.isStopped());

        // Now load
        DurableResult<String> loadRes = durableFlow.load("exec-stop-1");
        Assert.assertTrue(loadRes.isStopped());
        Assert.assertEquals("REJECTED", loadRes.stopReason().code());
        Assert.assertEquals("Too low", loadRes.stopReason().message());
    }

    @Test
    public void loadOnFailedExecution_decodesFailure() {
        Flow<String, String> flow = Flows.<String>begin("load-fail-flow")
                .step("fail-node", (Step<String, String>) (in) -> {
                    throw new IllegalStateException("Node crashed");
                })
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        DurableResult<String> startRes = durableFlow.start("exec-fail-1", "start");
        Assert.assertTrue(startRes.isFailed());

        // Now load
        DurableResult<String> loadRes = durableFlow.load("exec-fail-1");
        Assert.assertTrue(loadRes.isFailed());
        Assert.assertEquals("fail-node", loadRes.failure().nodeId());
        Assert.assertTrue(loadRes.failure().message().contains("Node crashed"));
    }

    @Test
    public void loadOnCancelledExecution_decodesCancelled() {
        Flow<String, String> flow = Flows.<String>begin("load-can-flow")
                .step("s1", in -> in)
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        DurableSnapshot init = DurableSnapshot.initial("load-can-flow", 1, "exec-can-1", StoredValue.ofString("in"));
        store.save(init, 0L);

        durableFlow.cancel("exec-can-1");

        DurableResult<String> loadRes = durableFlow.load("exec-can-1");
        Assert.assertTrue(loadRes.isCancelled());
    }

    @Test(expected = NoSuchElementException.class)
    public void loadNonExistentExecution_throwsNoSuchElementException() {
        Flow<String, String> flow = Flows.<String>begin("load-404-flow")
                .step("s1", in -> in)
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        durableFlow.load("non-existent-id");
    }

    // ==========================================
    // 2. 跨版本命令校验 (Cross-version rejection)
    // ==========================================

    @Test
    public void crossVersionCommands_rejectedAcrossAllOperations() {
        Flow<String, String> flow = Flows.<String>begin("cross-v-flow")
                .step("s1", in -> in)
                .build();

        DurableFlow<String, String> durableFlowV2 = runtime.register(flow, 2);

        // Pre-save snapshot with version 1
        DurableSnapshot snapV1 = DurableSnapshot.initial("cross-v-flow", 1, "exec-v1", StoredValue.ofString("in"));
        store.save(snapV1, 0L);

        // 1. recover should reject
        try {
            durableFlowV2.recover("exec-v1");
            Assert.fail("recover should reject version mismatch");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("version"));
        }

        // 2. retry should reject
        DurableSnapshot failedV1 = snapV1.withFailed(new DurableFailure("s1", "s1", "Ex", "err"));
        store.save(failedV1, snapV1.revision());

        try {
            durableFlowV2.retry("exec-v1");
            Assert.fail("retry should reject version mismatch");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("version"));
        }

        // 3. cancel should reject
        try {
            durableFlowV2.cancel("exec-v1");
            Assert.fail("cancel should reject version mismatch");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("version"));
        }

        // 4. load should reject
        try {
            durableFlowV2.load("exec-v1");
            Assert.fail("load should reject version mismatch");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("version"));
        }
    }

    // ==========================================
    // 3. 集中式元数据与生命周期校验
    // ==========================================

    @Test
    public void snapshotFlowIdMismatch_rejected() {
        Flow<String, String> flow = Flows.<String>begin("flow-expected")
                .step("s1", in -> in)
                .build();

        DurableStore customStore = new DurableStore() {
            @Override
            public DurableSnapshot load(String flowId, String executionId) {
                return DurableSnapshot.initial("flow-different", 1, executionId, StoredValue.ofString("in"));
            }
            @Override
            public boolean save(DurableSnapshot snapshot, long expectedRevision) {
                return true;
            }
        };

        DurableRuntime customRuntime = DurableRuntime.builder(customStore).build();
        DurableFlow<String, String> df = customRuntime.register(flow, 1);

        try {
            df.load("exec-1");
            Assert.fail("Expected IllegalStateException for flowId mismatch");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("flowId"));
        }

        try {
            df.recover("exec-1");
            Assert.fail("Expected IllegalStateException for flowId mismatch");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("flowId"));
        }
    }

    @Test
    public void snapshotExecutionIdMismatch_rejected() {
        Flow<String, String> flow = Flows.<String>begin("flow-exec-test")
                .step("s1", in -> in)
                .build();

        DurableStore customStore = new DurableStore() {
            @Override
            public DurableSnapshot load(String flowId, String executionId) {
                return DurableSnapshot.initial(flowId, 1, "mismatched-exec-id", StoredValue.ofString("in"));
            }
            @Override
            public boolean save(DurableSnapshot snapshot, long expectedRevision) {
                return true;
            }
        };

        DurableRuntime customRuntime = DurableRuntime.builder(customStore).build();
        DurableFlow<String, String> df = customRuntime.register(flow, 1);

        try {
            df.load("requested-exec-id");
            Assert.fail("Expected IllegalStateException for executionId mismatch");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("executionId"));
        }
    }

    @Test
    public void unsupportedFormatId_rejected() {
        Flow<String, String> flow = Flows.<String>begin("format-test-flow")
                .step("s1", in -> in)
                .build();

        // Save a snapshot with unsupported formatId
        DurableSnapshot badFormatSnap = new DurableSnapshot(
                "format-test-flow", 1, "exec-fmt-1",
                "custom-unsupported-format", 1, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(),
                Collections.emptyMap(), null, null
        );
        store.save(badFormatSnap, 0L);

        DurableFlow<String, String> df = runtime.register(flow, 1);

        try {
            df.load("exec-fmt-1");
            Assert.fail("Expected IllegalStateException for unsupported formatId");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("formatId"));
        }

        try {
            df.recover("exec-fmt-1");
            Assert.fail("Expected IllegalStateException for unsupported formatId");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("formatId"));
        }
    }

    @Test
    public void unsupportedFormatVersion_rejected() {
        Flow<String, String> flow = Flows.<String>begin("fmt-ver-flow")
                .step("s1", in -> in)
                .build();

        DurableSnapshot badFmtVerSnap = new DurableSnapshot(
                "fmt-ver-flow", 1, "exec-fmtver-1",
                DurableSnapshot.DEFAULT_FORMAT_ID, 99, 1L,
                DurableLifecycle.ACTIVE, FrameState.initial(),
                Collections.emptyMap(), null, null
        );
        store.save(badFmtVerSnap, 0L);

        DurableFlow<String, String> df = runtime.register(flow, 1);

        try {
            df.load("exec-fmtver-1");
            Assert.fail("Expected IllegalStateException for unsupported formatVersion");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("formatVersion"));
        }
    }

    @Test
    public void executionIdBlankOrNull_rejected() {
        Flow<String, String> flow = Flows.<String>begin("blank-exec-flow")
                .step("s1", in -> in)
                .build();

        DurableFlow<String, String> df = runtime.register(flow, 1);

        // start
        try {
            df.start(null, "val");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("executionId"));
        }

        try {
            df.start("  ", "val");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("executionId"));
        }

        // recover
        try {
            df.recover("  ");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("executionId"));
        }

        // retry
        try {
            df.retry("  ");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("executionId"));
        }

        // cancel
        try {
            df.cancel("  ");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("executionId"));
        }

        // load
        try {
            df.load("  ");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("executionId"));
        }
    }

    @Test
    public void startExistingExecution_rejected() {
        Flow<String, String> flow = Flows.<String>begin("dup-exec-flow")
                .step("s1", in -> in)
                .build();

        DurableFlow<String, String> df = runtime.register(flow, 1);
        df.start("exec-dup-1", "hello");

        try {
            df.start("exec-dup-1", "hello-again");
            Assert.fail("Expected IllegalStateException for duplicate execution");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("already exists"));
        }
    }
}
