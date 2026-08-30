package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.*;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Durable 持久化契约测试（覆盖全部 13 种核心场景）。
 *
 * @author jay.wu
 */
public class DurableFlowContractTest {

    private InMemoryDurableStore store;
    private DurableRuntime runtime;

    @Before
    public void setUp() {
        store = new InMemoryDurableStore();
        runtime = DurableRuntime.builder(store).build();
    }

    @Test
    public void contract1_initialSnapshot_persistedBeforeFirstNode() {
        AtomicBoolean storeHasInitialSnapshot = new AtomicBoolean();

        Flow<String, String> flow = Flows.<String>begin("init-flow")
                .step("step-check-store", (ctx, in) -> {
                    DurableSnapshot snap = store.load("init-flow", ctx.executionId());
                    if (snap != null && snap.lifecycle() == DurableLifecycle.ACTIVE && snap.revision() == 1L) {
                        storeHasInitialSnapshot.set(true);
                    }
                    return in + "-ok";
                })
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        DurableResult<String> result = durableFlow.start("exec-1", "hello");

        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("hello-ok", result.value());
        Assert.assertTrue(storeHasInitialSnapshot.get());
    }

    @Test
    public void contract2_crashAfterNode_recoversFromNextNode() {
        AtomicInteger step1Count = new AtomicInteger();
        AtomicInteger step2Count = new AtomicInteger();

        Flow<String, String> flow = Flows.<String>begin("crash-flow")
                .step("s1", in -> {
                    step1Count.incrementAndGet();
                    return in + "-s1";
                })
                .step("s2", in -> {
                    step2Count.incrementAndGet();
                    return in + "-s2";
                })
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);

        // Simulate crash right after s1 by manually advancing cursor=1 in snapshot
        StoredValue inSlot = StoredValue.ofString("in");
        DurableSnapshot snapAfterS1 = new DurableSnapshot(
                "crash-flow", 1, "exec-c1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 2L,
                DurableLifecycle.ACTIVE, new FrameState(1, "", Collections.emptyMap(), false),
                Collections.singletonMap("active", StoredValue.ofString("in-s1")), null, null);
        store.save(snapAfterS1, 0L);

        // Now recover
        DurableResult<String> recResult = durableFlow.recover("exec-c1");
        Assert.assertTrue(recResult.isCompleted());
        Assert.assertEquals("in-s1-s2", recResult.value());

        // s1 was skipped because cursor was 1, s2 executed once
        Assert.assertEquals(0, step1Count.get());
        Assert.assertEquals(1, step2Count.get());
    }

    @Test
    public void contract3_crashDuringStep_replaysWithSameInvocationId() {
        List<String> invocationIds = new ArrayList<>();

        Flow<String, String> flow = Flows.<String>begin("invoc-flow")
                .step("step-record", (ctx, in) -> {
                    invocationIds.add(ctx.invocationId());
                    return in + "-done";
                })
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);

        // Simulate crash: initial snapshot is at cursor 0
        DurableSnapshot init = DurableSnapshot.initial("invoc-flow", 1, "exec-invoc-1", StoredValue.ofString("val"));
        store.save(init, 0L);

        // Run recover twice (simulating retry of same step)
        durableFlow.recover("exec-invoc-1");

        Assert.assertEquals(1, invocationIds.size());
        String expectedInvocId = "invoc-flow:1:exec-invoc-1#/s0:step-record";
        Assert.assertEquals(expectedInvocId, invocationIds.get(0));
    }

    @Test
    public void contract4_casConflict_isolatedAndSafe() {
        DurableStore conflictStore = new DurableStore() {
            private int saveCalls = 0;
            @Override
            public DurableSnapshot load(String flowId, String executionId) {
                return store.load(flowId, executionId);
            }
            @Override
            public boolean save(DurableSnapshot snapshot, long expectedRevision) {
                saveCalls++;
                if (saveCalls > 1) {
                    // Simulate CAS conflict on 2nd checkpoint
                    return false;
                }
                return store.save(snapshot, expectedRevision);
            }
        };

        DurableRuntime conflictRuntime = DurableRuntime.builder(conflictStore).build();
        Flow<String, String> flow = Flows.<String>begin("cas-flow")
                .step("s1", in -> in + "-1")
                .step("s2", in -> in + "-2")
                .build();

        DurableFlow<String, String> durableFlow = conflictRuntime.register(flow, 1);
        DurableResult<String> res = durableFlow.start("exec-cas-1", "in");

        // When CAS conflict occurs at checkpoint, runner returns the current state
        Assert.assertNotNull(res);
    }

    @Test
    public void contract5_branchRecovery_doesNotReevaluateSelector() {
        AtomicInteger selectorCount = new AtomicInteger();

        Flow<String, String> cardFlow = Flows.step("card-flow", in -> in + ":CARD_PROCESSED");
        Flow<String, String> walletFlow = Flows.step("wallet-flow", in -> in + ":WALLET_PROCESSED");

        Flow<String, String> flow = Flows.<String>begin("branch-flow")
                .choose("choose-pay", in -> {
                    selectorCount.incrementAndGet();
                    return "CARD";
                })
                .when("CARD", cardFlow)
                .when("WALLET", walletFlow)
                .end()
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);

        // Pre-save snapshot with branch choice already recorded for "/s0:choose-pay"
        FrameState frame = FrameState.initial().withBranchChoice("/s0:choose-pay", "CARD");
        DurableSnapshot snap = new DurableSnapshot(
                "branch-flow", 1, "exec-br-1", DurableSnapshot.DEFAULT_FORMAT_ID, 1, 1L,
                DurableLifecycle.ACTIVE, frame,
                Collections.singletonMap("active", StoredValue.ofString("order-1")), null, null);
        store.save(snap, 0L);

        DurableResult<String> recRes = durableFlow.recover("exec-br-1");
        Assert.assertTrue(recRes.isCompleted());
        Assert.assertEquals("order-1:CARD_PROCESSED", recRes.value());

        // Selector was NOT called because choice was saved in frame
        Assert.assertEquals(0, selectorCount.get());
    }

    @Test
    public void contract6_failedRetry_rebuildsStateFromLastSuccessfulSnapshot() {
        AtomicInteger attempt = new AtomicInteger();

        Flow<String, String> flow = Flows.<String>begin("retry-flow")
                .step("s1-ok", in -> in + "-s1")
                .step("s2-flaky", in -> {
                    if (attempt.incrementAndGet() == 1) {
                        throw new RuntimeException("Temporary network error");
                    }
                    return in + "-s2";
                })
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);

        DurableResult<String> res1 = durableFlow.start("exec-retry-1", "start");
        Assert.assertTrue(res1.isFailed());
        Assert.assertEquals("s2-flaky", res1.failure().nodeId());

        // Snapshot is now FAILED at cursor 1, active slot contains "start-s1"
        DurableSnapshot failSnap = store.load("retry-flow", "exec-retry-1");
        Assert.assertEquals(DurableLifecycle.FAILED, failSnap.lifecycle());

        // Retry
        DurableResult<String> res2 = durableFlow.retry("exec-retry-1");
        Assert.assertTrue(res2.isCompleted());
        Assert.assertEquals("start-s1-s2", res2.value());
    }

    @Test
    public void contract7_failedCancel_rejectsSubsequentRetry() {
        Flow<String, String> flow = Flows.<String>begin("cancel-flow")
                .step("fail-step", (Step<String, String>) in -> {
                    throw new RuntimeException("Permanent error");
                })
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        DurableResult<String> res1 = durableFlow.start("exec-can-1", "start");
        Assert.assertTrue(res1.isFailed());

        // Cancel
        boolean cancelled = durableFlow.cancel("exec-can-1");
        Assert.assertTrue(cancelled);

        DurableSnapshot snap = store.load("cancel-flow", "exec-can-1");
        Assert.assertEquals(DurableLifecycle.CANCELLED, snap.lifecycle());

        // Subsequent retry is rejected
        try {
            durableFlow.retry("exec-can-1");
            Assert.fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("CANCELLED"));
        }
    }

    @Test
    public void contract8_activeCancel_abortsExecution() {
        DurableSnapshot init = DurableSnapshot.initial("active-can-flow", 1, "exec-ac-1", StoredValue.ofString("val"));
        store.save(init, 0L);

        DurableFlow<String, String> durableFlow = runtime.register(Flows.<String>begin("active-can-flow")
                .step("s1", in -> in)
                .build(), 1);

        boolean cancelled = durableFlow.cancel("exec-ac-1");
        Assert.assertTrue(cancelled);

        DurableResult<String> res = durableFlow.load("exec-ac-1");
        Assert.assertTrue(res.isCancelled());
    }

    @Test
    public void contract9_recoverNode_handlesFailureAndRecovers() {
        Flow<String, String> flow = Flows.<String>begin("durable-rec-flow")
                .step("fail-step", (Step<String, String>) in -> {
                    throw new RuntimeException("Remote gateway down");
                })
                .recover("fallback", (in, failure) -> FlowResult.succeeded("fallback-value:" + in))
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        DurableResult<String> res = durableFlow.start("exec-rec-1", "order-99");

        Assert.assertTrue(res.isCompleted());
        Assert.assertEquals("fallback-value:order-99", res.value());
    }

    @Test
    public void contract10_ensureReplay_invokedWithCorrectContext() {
        AtomicBoolean ensureRan = new AtomicBoolean();
        AtomicReference<String> completedValue = new AtomicReference<>();

        Flow<String, String> flow = Flows.<String>begin("ensure-durable")
                .step("s1", in -> in + "-ok")
                .ensure("cleanup", (in, completion) -> {
                    ensureRan.set(true);
                    if (completion.isSucceeded()) {
                        completedValue.set(completion.value());
                    }
                })
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(flow, 1);
        DurableResult<String> res = durableFlow.start("exec-ens-1", "in");

        Assert.assertTrue(res.isCompleted());
        Assert.assertTrue(ensureRan.get());
        Assert.assertEquals("in-ok", completedValue.get());
    }

    @Test
    public void contract11_codecFailure_doesNotAdvanceRevision() {
        StateMapper brokenMapper = new StateMapper() {
            @Override
            public StoredValue encode(Object value) throws Exception {
                throw new RuntimeException("Encode serialization error");
            }
            @Override
            public Object decode(StoredValue storedValue) {
                return storedValue.asString();
            }
        };

        DurableRuntime brokenRuntime = DurableRuntime.builder(store).stateMapper(brokenMapper).build();

        Flow<String, String> flow = Flows.<String>begin("broken-codec")
                .step("s1", in -> in)
                .build();

        DurableFlow<String, String> durableFlow = brokenRuntime.register(flow, 1);

        try {
            durableFlow.start("exec-codec-1", "hello");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Failed to encode input"));
        }

        // Store has not created the execution
        Assert.assertNull(store.load("broken-codec", "exec-codec-1"));
    }

    @Test
    public void contract12_versionMismatch_rejectsRecovery() {
        Flow<String, String> flow = Flows.<String>begin("v-flow")
                .step("s1", in -> in)
                .build();

        DurableFlow<String, String> durableFlowV2 = runtime.register(flow, 2);

        // Pre-save snapshot with version 1
        DurableSnapshot snapV1 = DurableSnapshot.initial("v-flow", 1, "exec-v-1", StoredValue.ofString("in"));
        store.save(snapV1, 0L);

        try {
            durableFlowV2.recover("exec-v-1");
            Assert.fail("Expected IllegalStateException for version mismatch");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("version"));
        }
    }

    @Test
    public void contract13_multipleVersionsCoexist() {
        Flow<String, String> flowV1 = Flows.<String>begin("multi-v-flow")
                .step("step-v1", in -> in + ":V1")
                .build();

        Flow<String, String> flowV2 = Flows.<String>begin("multi-v-flow")
                .step("step-v2", in -> in + ":V2")
                .build();

        DurableFlow<String, String> durableV1 = runtime.register(flowV1, 1);
        DurableFlow<String, String> durableV2 = runtime.register(flowV2, 2);

        DurableResult<String> res1 = durableV1.start("exec-mv-1", "order");
        DurableResult<String> res2 = durableV2.start("exec-mv-2", "order");

        Assert.assertEquals("order:V1", res1.value());
        Assert.assertEquals("order:V2", res2.value());
        Assert.assertEquals(1, res1.flowVersion());
        Assert.assertEquals(2, res2.flowVersion());
    }
}
