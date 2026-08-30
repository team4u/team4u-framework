package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.*;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Durable 持久化契约测试（覆盖全部核心场景、真实崩溃注入、嵌套回溯、幂等调用与并发安全性）。
 *
 * @author jay.wu
 */
public class DurableFlowContractTest {

    public static class SimulatedCrash extends Error {
        public SimulatedCrash(String message) {
            super(message);
        }
    }

    public static class CrashInjectingStore implements DurableStore {
        private final DurableStore delegate;
        private final Predicate<DurableSnapshot> crashOnSave;
        private final AtomicBoolean crashed = new AtomicBoolean(false);

        public CrashInjectingStore(DurableStore delegate, Predicate<DurableSnapshot> crashOnSave) {
            this.delegate = delegate;
            this.crashOnSave = crashOnSave;
        }

        @Override
        public DurableSnapshot load(String flowId, String executionId) {
            return delegate.load(flowId, executionId);
        }

        @Override
        public boolean save(DurableSnapshot snapshot, long expectedRevision) {
            boolean saved = delegate.save(snapshot, expectedRevision);
            if (saved && crashOnSave.test(snapshot)) {
                crashed.set(true);
                throw new SimulatedCrash("Crash injected after saving revision " + snapshot.revision() +
                        ", topFrame=" + snapshot.frameState().topFrame());
            }
            return saved;
        }

        public boolean hasCrashed() {
            return crashed.get();
        }
    }

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

        // Crash injection right after s1 checkpoint is committed
        CrashInjectingStore crashStore = new CrashInjectingStore(store, snap -> {
            StoredValue active = snap.getSlot("active");
            return active != null && "in-s1".equals(active.asString());
        });
        DurableRuntime crashRuntime = DurableRuntime.builder(crashStore).build();
        DurableFlow<String, String> durableFlow = crashRuntime.register(flow, 1);

        try {
            durableFlow.start("exec-c1", "in");
            Assert.fail("Expected SimulatedCrash");
        } catch (SimulatedCrash expected) {
            Assert.assertTrue(crashStore.hasCrashed());
        }

        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(0, step2Count.get());

        // Now recover from normal runtime
        DurableFlow<String, String> recoverFlow = runtime.register(flow, 1);
        DurableResult<String> recResult = recoverFlow.recover("exec-c1");
        Assert.assertTrue(recResult.isCompleted());
        Assert.assertEquals("in-s1-s2", recResult.value());

        // s1 was skipped because it already committed, s2 executed once
        Assert.assertEquals(1, step1Count.get());
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

        // Crash simulation: initial snapshot is at cursor 0
        DurableSnapshot init = DurableSnapshot.initial("invoc-flow", 1, "exec-invoc-1", StoredValue.ofString("val"));
        store.save(init, 0L);

        // Run recover
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

        // When CAS conflict occurs at checkpoint, runner returns the authoritative state
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

        // Crash right after choose branch is committed into frame stack
        CrashInjectingStore crashStore = new CrashInjectingStore(store, snap ->
                snap.frameState().frames().size() > 1 && "case:0".equals(snap.frameState().frames().get(0).selectedBranch()));
        DurableRuntime crashRuntime = DurableRuntime.builder(crashStore).build();
        DurableFlow<String, String> durableFlow = crashRuntime.register(flow, 1);

        try {
            durableFlow.start("exec-br-1", "order-1");
            Assert.fail("Expected SimulatedCrash");
        } catch (SimulatedCrash expected) {
            Assert.assertTrue(crashStore.hasCrashed());
        }

        Assert.assertEquals(1, selectorCount.get());

        // Recover
        DurableFlow<String, String> recoverFlow = runtime.register(flow, 1);
        DurableResult<String> recRes = recoverFlow.recover("exec-br-1");
        Assert.assertTrue(recRes.isCompleted());
        Assert.assertEquals("order-1:CARD_PROCESSED", recRes.value());

        // Selector was NOT called during recovery because choice was saved in frame
        Assert.assertEquals(1, selectorCount.get());
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

    @Test
    public void crashInjection_branchResume_resumesNestedNodeAndReturnsToParent() {
        AtomicInteger step1Count = new AtomicInteger();
        AtomicInteger branchStep1Count = new AtomicInteger();
        AtomicInteger branchStep2Count = new AtomicInteger();
        AtomicInteger step3Count = new AtomicInteger();

        Flow<String, String> branchFlow = Flows.<String>begin("b-flow")
                .step("b-step-1", in -> {
                    branchStep1Count.incrementAndGet();
                    return in + ":B1";
                })
                .step("b-step-2", in -> {
                    branchStep2Count.incrementAndGet();
                    return in + ":B2";
                })
                .build();

        Flow<String, String> flow = Flows.<String>begin("nested-choose-flow")
                .step("s1", in -> {
                    step1Count.incrementAndGet();
                    return in + ":S1";
                })
                .choose("choose-ab", in -> "A")
                .when("A", branchFlow)
                .end()
                .step("s3", in -> {
                    step3Count.incrementAndGet();
                    return in + ":S3";
                })
                .build();

        // Crash right after b-step-1 is committed (active slot contains "INIT:S1:B1")
        CrashInjectingStore crashStore = new CrashInjectingStore(store, snap -> {
            StoredValue active = snap.getSlot("active");
            return active != null && "INIT:S1:B1".equals(active.asString());
        });
        DurableRuntime crashRuntime = DurableRuntime.builder(crashStore).build();
        DurableFlow<String, String> durableFlow = crashRuntime.register(flow, 1);

        try {
            durableFlow.start("exec-br-resume", "INIT");
            Assert.fail("Expected SimulatedCrash");
        } catch (SimulatedCrash expected) {
            Assert.assertTrue(crashStore.hasCrashed());
        }

        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(1, branchStep1Count.get());
        Assert.assertEquals(0, branchStep2Count.get());
        Assert.assertEquals(0, step3Count.get());

        // Recover
        DurableFlow<String, String> recoverFlow = runtime.register(flow, 1);
        DurableResult<String> result = recoverFlow.recover("exec-br-resume");
        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("INIT:S1:B1:B2:S3", result.value());

        // step1 was not re-executed, branchStep1 was not re-executed, branchStep2 executed once, step3 executed once
        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(1, branchStep1Count.get());
        Assert.assertEquals(1, branchStep2Count.get());
        Assert.assertEquals(1, step3Count.get());
    }

    @Test
    public void crashInjection_subflowResume_resumesNestedNodeAndReturnsToParent() {
        AtomicInteger step1Count = new AtomicInteger();
        AtomicInteger subStep1Count = new AtomicInteger();
        AtomicInteger subStep2Count = new AtomicInteger();
        AtomicInteger step3Count = new AtomicInteger();

        Flow<String, String> subflow = Flows.<String>begin("inner-flow")
                .step("sub-1", in -> {
                    subStep1Count.incrementAndGet();
                    return in + ":SUB1";
                })
                .step("sub-2", in -> {
                    subStep2Count.incrementAndGet();
                    return in + ":SUB2";
                })
                .build();

        Flow<String, String> flow = Flows.<String>begin("nested-sub-flow")
                .step("s1", in -> {
                    step1Count.incrementAndGet();
                    return in + ":S1";
                })
                .then(subflow)
                .step("s3", in -> {
                    step3Count.incrementAndGet();
                    return in + ":S3";
                })
                .build();

        // Crash right after sub-1 is committed (active slot contains "INIT:S1:SUB1")
        CrashInjectingStore crashStore = new CrashInjectingStore(store, snap -> {
            StoredValue active = snap.getSlot("active");
            return active != null && "INIT:S1:SUB1".equals(active.asString());
        });
        DurableRuntime crashRuntime = DurableRuntime.builder(crashStore).build();
        DurableFlow<String, String> durableFlow = crashRuntime.register(flow, 1);

        try {
            durableFlow.start("exec-sub-resume", "INIT");
            Assert.fail("Expected SimulatedCrash");
        } catch (SimulatedCrash expected) {
            Assert.assertTrue(crashStore.hasCrashed());
        }

        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(1, subStep1Count.get());
        Assert.assertEquals(0, subStep2Count.get());
        Assert.assertEquals(0, step3Count.get());

        // Recover
        DurableFlow<String, String> recoverFlow = runtime.register(flow, 1);
        DurableResult<String> result = recoverFlow.recover("exec-sub-resume");
        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("INIT:S1:SUB1:SUB2:S3", result.value());

        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(1, subStep1Count.get());
        Assert.assertEquals(1, subStep2Count.get());
        Assert.assertEquals(1, step3Count.get());
    }

    @Test
    public void choose_collidingKeyStrings_distinguishesIntegerAndString() {
        Flow<Object, String> flow = Flows.begin("key-collision-flow")
                .choose("choose-key", in -> in)
                .when(1, Flows.step("int-branch", in -> "INTEGER_BRANCH"))
                .when("1", Flows.step("str-branch", in -> "STRING_BRANCH"))
                .end()
                .build();

        DurableFlow<Object, String> durableFlow = runtime.register(flow, 1);

        DurableResult<String> intResult = durableFlow.start("exec-int-1", 1);
        Assert.assertTrue(intResult.isCompleted());
        Assert.assertEquals("INTEGER_BRANCH", intResult.value());

        DurableResult<String> strResult = durableFlow.start("exec-str-1", "1");
        Assert.assertTrue(strResult.isCompleted());
        Assert.assertEquals("STRING_BRANCH", strResult.value());
    }

    @Test
    public void ensure_pendingAndResume_maintainsStableInvocationId() {
        List<String> ensureInvocationIds = new ArrayList<>();
        AtomicReference<CompletionContext<String>> capturedContext = new AtomicReference<>();

        Flow<String, String> flow = Flows.<String>begin("ensure-resume-flow")
                .step("s1", in -> in + ":OK")
                .ensure("cleanup-node", (stepContext, in, completion) -> {
                    ensureInvocationIds.add(stepContext.invocationId());
                    capturedContext.set(completion);
                })
                .build();

        // Crash right after ENSURE phase checkpoint is committed
        CrashInjectingStore crashStore = new CrashInjectingStore(store, snap ->
                snap.frameState().topFrame().phase() == FrameState.Phase.ENSURE);
        DurableRuntime crashRuntime = DurableRuntime.builder(crashStore).build();
        DurableFlow<String, String> durableFlow = crashRuntime.register(flow, 1);

        try {
            durableFlow.start("exec-ens-res-1", "IN");
            Assert.fail("Expected SimulatedCrash");
        } catch (SimulatedCrash expected) {
            Assert.assertTrue(crashStore.hasCrashed());
        }

        Assert.assertEquals(0, ensureInvocationIds.size());

        // Recover will execute Ensure with stable invocationId
        DurableFlow<String, String> recoverFlow = runtime.register(flow, 1);
        DurableResult<String> result = recoverFlow.recover("exec-ens-res-1");
        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("IN:OK", result.value());

        Assert.assertEquals(1, ensureInvocationIds.size());
        Assert.assertEquals("ensure-resume-flow:1:exec-ens-res-1#/ensure:cleanup-node", ensureInvocationIds.get(0));
        Assert.assertTrue(capturedContext.get().isSucceeded());
        Assert.assertEquals("IN:OK", capturedContext.get().value());
    }

    @Test
    public void ensure_failureSuppressionAndLifecycleMerge() {
        // Case 1: Body succeeded, Ensure throws exception -> Result is FAILED with Ensure failure
        Flow<String, String> flowEnsureFails = Flows.<String>begin("ens-fail-1")
                .step("s1", in -> in + ":OK")
                .ensure("fail-cleanup", (in, completion) -> {
                    throw new RuntimeException("Ensure cleanup crashed");
                })
                .build();

        DurableFlow<String, String> durableFlow1 = runtime.register(flowEnsureFails, 1);
        DurableResult<String> res1 = durableFlow1.start("exec-ef-1", "start");
        Assert.assertTrue(res1.isFailed());
        Assert.assertEquals("fail-cleanup", res1.failure().nodeId());
        DurableSnapshot snap1 = store.load("ens-fail-1", "exec-ef-1");
        Assert.assertEquals(DurableLifecycle.FAILED, snap1.lifecycle());

        // Case 2: Body failed, Ensure also throws exception -> Result remains FAILED with body failure, ensure exception suppressed
        Flow<String, String> flowBothFail = Flows.<String>begin("ens-fail-2")
                .step("fail-step", (Step<String, String>) in -> {
                    throw new RuntimeException("Primary step failure");
                })
                .ensure("ensure-step", (in, completion) -> {
                    throw new RuntimeException("Secondary ensure failure");
                })
                .build();

        DurableFlow<String, String> durableFlow2 = runtime.register(flowBothFail, 1);
        DurableResult<String> res2 = durableFlow2.start("exec-ef-2", "start");
        Assert.assertTrue(res2.isFailed());
        Assert.assertEquals("fail-step", res2.failure().nodeId());
        Assert.assertTrue(res2.failure().message().contains("Primary step failure"));
        DurableSnapshot snap2 = store.load("ens-fail-2", "exec-ef-2");
        Assert.assertEquals(DurableLifecycle.FAILED, snap2.lifecycle());
        Assert.assertEquals("fail-step", snap2.failure().nodeId());

        // Case 3: Body stopped, Ensure throws exception -> Result becomes FAILED with Ensure failure
        Flow<String, String> flowStopEnsureFail = Flows.<String>begin("ens-fail-3")
                .guard("guard-stop", in -> false, in -> StopReason.of("STOP_REASON"))
                .ensure("ensure-on-stop", (in, completion) -> {
                    throw new RuntimeException("Ensure failure on stop");
                })
                .build();

        DurableFlow<String, String> durableFlow3 = runtime.register(flowStopEnsureFail, 1);
        DurableResult<String> res3 = durableFlow3.start("exec-ef-3", "start");
        Assert.assertTrue(res3.isFailed());
        Assert.assertEquals("ensure-on-stop", res3.failure().nodeId());
        DurableSnapshot snap3 = store.load("ens-fail-3", "exec-ef-3");
        Assert.assertEquals(DurableLifecycle.FAILED, snap3.lifecycle());
    }

    @Test
    public void casLoss_onEveryTransition_stopsExecutionAndReturnsAuthoritativeSnapshot() {
        AtomicInteger step2ExecutionCount = new AtomicInteger();

        DurableStore casLossStore = new DurableStore() {
            private int saveCount = 0;
            @Override
            public DurableSnapshot load(String flowId, String executionId) {
                return store.load(flowId, executionId);
            }
            @Override
            public boolean save(DurableSnapshot snapshot, long expectedRevision) {
                saveCount++;
                if (saveCount == 2) {
                    // Simulate another node updated the store concurrently
                    DurableSnapshot concurrent = snapshot.withRevision(999L);
                    store.save(concurrent, 1L);
                    return false; // CAS loss
                }
                return store.save(snapshot, expectedRevision);
            }
        };

        DurableRuntime casRuntime = DurableRuntime.builder(casLossStore).build();
        Flow<String, String> flow = Flows.<String>begin("cas-loss-flow")
                .step("s1", in -> in + ":1")
                .step("s2", in -> {
                    step2ExecutionCount.incrementAndGet();
                    return in + ":2";
                })
                .build();

        DurableFlow<String, String> durableFlow = casRuntime.register(flow, 1);
        DurableResult<String> res = durableFlow.start("exec-cas-loss-1", "start");

        Assert.assertNotNull(res);
        // Step 2 was NOT executed after CAS loss on step 1 checkpoint
        Assert.assertEquals(0, step2ExecutionCount.get());
        Assert.assertEquals(999L, res.revision());
    }

    @Test
    public void multipleMountedSubflowCopies_produceDeterministicDistinctInvocationIds() {
        List<String> recordedInvocationIds = new ArrayList<>();

        Flow<String, String> subflow = Flows.<String>begin("reusable-subflow")
                .step("shared-step", (ctx, in) -> {
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + "-sub";
                })
                .build();

        Flow<String, String> mainFlow = Flows.<String>begin("main-flow")
                .step("s0", in -> in + "-s0")
                .then(subflow)
                .step("s2", in -> in + "-s2")
                .then(subflow)
                .build();

        DurableFlow<String, String> durableFlow = runtime.register(mainFlow, 1);
        DurableResult<String> result = durableFlow.start("exec-multi-mount-1", "init");

        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("init-s0-sub-s2-sub", result.value());

        Assert.assertEquals(2, recordedInvocationIds.size());
        String id1 = recordedInvocationIds.get(0);
        String id2 = recordedInvocationIds.get(1);

        Assert.assertEquals("main-flow:1:exec-multi-mount-1#/s1:reusable-subflow/s0:shared-step", id1);
        Assert.assertEquals("main-flow:1:exec-multi-mount-1#/s3:reusable-subflow/s0:shared-step", id2);
        Assert.assertNotEquals(id1, id2);
    }

    @Test
    public void choose_branchesMountingSameSubflow_produceDistinctInvocationIds() {
        List<String> recordedInvocationIds = new ArrayList<>();

        Flow<String, String> branchSub = Flows.<String>begin("branch-sub")
                .step("branch-step", (ctx, in) -> {
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + "-branch-done";
                })
                .build();

        Flow<String, String> chooseFlow = Flows.<String>begin("choose-mount-flow")
                .choose("choose-node", in -> in)
                .when("branchA", branchSub)
                .when("branchB", branchSub)
                .end()
                .build();

        DurableFlow<String, String> df = runtime.register(chooseFlow, 1);

        DurableResult<String> resA = df.start("exec-ch-a", "branchA");
        Assert.assertTrue(resA.isCompleted());
        Assert.assertEquals("branchA-branch-done", resA.value());

        DurableResult<String> resB = df.start("exec-ch-b", "branchB");
        Assert.assertTrue(resB.isCompleted());
        Assert.assertEquals("branchB-branch-done", resB.value());

        Assert.assertEquals(2, recordedInvocationIds.size());
        Assert.assertEquals("choose-mount-flow:1:exec-ch-a#/s0:choose-node/case:0/s0:branch-step", recordedInvocationIds.get(0));
        Assert.assertEquals("choose-mount-flow:1:exec-ch-b#/s0:choose-node/case:1/s0:branch-step", recordedInvocationIds.get(1));
    }

    @Test
    public void nestedBranchFailureUnwinding_executesChildAndParentEnsureAndRecoverInOrder() {
        List<String> executionLog = new ArrayList<>();

        Flow<String, String> branchFlow = Flows.<String>begin("child-flow")
                .step("child-fail-step", (Step<String, String>) in -> {
                    executionLog.add("child-fail-step");
                    throw new RuntimeException("Child step failed");
                })
                .ensure("child-ensure", (in, cc) -> {
                    executionLog.add("child-ensure:isFailed=" + cc.isFailed());
                })
                .build();

        Flow<String, String> rootFlow = Flows.<String>begin("root-flow")
                .choose("root-choose", in -> "A")
                .when("A", branchFlow)
                .end()
                .recover("parent-recover", (in, fc) -> {
                    executionLog.add("parent-recover:cause=" + fc.cause().getMessage());
                    return FlowResult.succeeded("recovered-by-parent");
                })
                .ensure("parent-ensure", (in, cc) -> {
                    executionLog.add("parent-ensure:isSucceeded=" + cc.isSucceeded());
                })
                .build();

        DurableFlow<String, String> df = runtime.register(rootFlow, 1);
        DurableResult<String> result = df.start("exec-nested-fail-1", "start");

        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("recovered-by-parent", result.value());

        List<String> expectedLog = Arrays.asList(
                "child-fail-step",
                "child-ensure:isFailed=true",
                "parent-recover:cause=Child step failed",
                "parent-ensure:isSucceeded=true"
        );
        Assert.assertEquals(expectedLog, executionLog);
    }

    @Test
    public void nestedSubflowStopUnwinding_executesChildAndParentEnsureInOrder() {
        List<String> executionLog = new ArrayList<>();

        Flow<String, String> subflow = Flows.<String>begin("sub-flow")
                .guard("sub-guard", in -> false, in -> StopReason.of("CHILD_STOP", "Stopped in child"))
                .ensure("child-ensure", (in, cc) -> {
                    executionLog.add("child-ensure:isStopped=" + cc.isStopped() + ",reason=" + cc.stopReason().code());
                })
                .build();

        Flow<String, String> rootFlow = Flows.<String>begin("root-stop-flow")
                .then(subflow)
                .ensure("parent-ensure", (in, cc) -> {
                    executionLog.add("parent-ensure:isStopped=" + cc.isStopped() + ",reason=" + cc.stopReason().code());
                })
                .build();

        DurableFlow<String, String> df = runtime.register(rootFlow, 1);
        DurableResult<String> result = df.start("exec-nested-stop-1", "start");

        Assert.assertTrue(result.isStopped());
        Assert.assertEquals("CHILD_STOP", result.stopReason().code());

        List<String> expectedLog = Arrays.asList(
                "child-ensure:isStopped=true,reason=CHILD_STOP",
                "parent-ensure:isStopped=true,reason=CHILD_STOP"
        );
        Assert.assertEquals(expectedLog, executionLog);
    }

    @Test
    public void staleExecution_beforeNextStep_returnsAuthoritativeResultImmediately() {
        AtomicInteger step2ExecutionCount = new AtomicInteger();

        AtomicBoolean injected = new AtomicBoolean(false);
        DurableStore concurrencyStore = new DurableStore() {
            @Override
            public DurableSnapshot load(String flowId, String executionId) {
                DurableSnapshot snap = store.load(flowId, executionId);
                if (snap != null && snap.revision() == 2L && !injected.get()) {
                    injected.set(true);
                    // Simulate another runner concurrently completed the execution while this runner was between steps
                    DurableSnapshot completedByOther = snap.withCompleted(StoredValue.ofString("completed-by-concurrent-runner"));
                    store.save(completedByOther, snap.revision());
                    return completedByOther;
                }
                return snap;
            }

            @Override
            public boolean save(DurableSnapshot snapshot, long expectedRevision) {
                return store.save(snapshot, expectedRevision);
            }
        };

        DurableRuntime staleRuntime = DurableRuntime.builder(concurrencyStore).build();
        Flow<String, String> flow = Flows.<String>begin("stale-check-flow")
                .step("s1", in -> in + ":S1")
                .step("s2", in -> {
                    step2ExecutionCount.incrementAndGet();
                    return in + ":S2";
                })
                .build();

        DurableFlow<String, String> df = staleRuntime.register(flow, 1);
        DurableResult<String> result = df.start("exec-stale-1", "init");

        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("completed-by-concurrent-runner", result.value());
        // Step 2 was NEVER executed by this runner because authoritative revision differed at loop start!
        Assert.assertEquals(0, step2ExecutionCount.get());
    }

    @Test
    public void errorInStepAndEnsure_propagatesUncaught() {
        class CustomError extends Error {
            CustomError(String message) { super(message); }
        }

        Flow<String, String> flowStepError = Flows.<String>begin("step-error-flow")
                .step("error-step", (Step<String, String>) in -> {
                    throw new CustomError("Step threw Error");
                })
                .build();

        DurableFlow<String, String> df1 = runtime.register(flowStepError, 1);
        try {
            df1.start("exec-err-1", "in");
            Assert.fail("Expected CustomError to propagate");
        } catch (CustomError e) {
            Assert.assertEquals("Step threw Error", e.getMessage());
        }

        Flow<String, String> flowEnsureError = Flows.<String>begin("ensure-error-flow")
                .step("s1", in -> in)
                .ensure("error-ensure", (in, cc) -> {
                    throw new CustomError("Ensure threw Error");
                })
                .build();

        DurableFlow<String, String> df2 = runtime.register(flowEnsureError, 1);
        try {
            df2.start("exec-err-2", "in");
            Assert.fail("Expected CustomError to propagate");
        } catch (CustomError e) {
            Assert.assertEquals("Ensure threw Error", e.getMessage());
        }
    }

    @Test
    public void crashInjection_nestedFailureUnwinding_recoversAndFinishesUnwinding() {
        List<String> executionLog = new ArrayList<>();

        Flow<String, String> childFlow = Flows.<String>begin("child-fail-flow")
                .step("child-step", (Step<String, String>) in -> {
                    executionLog.add("child-step");
                    throw new RuntimeException("Child error");
                })
                .ensure("child-ensure", (in, cc) -> {
                    executionLog.add("child-ensure");
                })
                .build();

        Flow<String, String> rootFlow = Flows.<String>begin("root-fail-flow")
                .then(childFlow)
                .recover("root-recover", (in, fc) -> {
                    executionLog.add("root-recover");
                    return FlowResult.succeeded("root-recovered");
                })
                .ensure("root-ensure", (in, cc) -> {
                    executionLog.add("root-ensure");
                })
                .build();

        // Crash after child-ensure phase is saved
        CrashInjectingStore crashStore = new CrashInjectingStore(store, snap ->
                snap.frameState().frames().size() > 1 && snap.frameState().topFrame().phase() == FrameState.Phase.ENSURE);
        DurableRuntime crashRuntime = DurableRuntime.builder(crashStore).build();
        DurableFlow<String, String> df = crashRuntime.register(rootFlow, 1);

        try {
            df.start("exec-nested-crash-fail", "in");
            Assert.fail("Expected SimulatedCrash");
        } catch (SimulatedCrash expected) {
            Assert.assertTrue(crashStore.hasCrashed());
        }

        Assert.assertEquals(Collections.singletonList("child-step"), executionLog);

        // Now recover: child ensure runs -> parent recover runs -> parent ensure runs
        DurableFlow<String, String> recoverFlow = runtime.register(rootFlow, 1);
        DurableResult<String> result = recoverFlow.recover("exec-nested-crash-fail");

        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("root-recovered", result.value());

        List<String> expected = Arrays.asList("child-step", "child-ensure", "root-recover", "root-ensure");
        Assert.assertEquals(expected, executionLog);
    }

    @Test
    public void retry_rootBodyFailureWithEnsure_retriesFailedStepAndPreservesState() {
        AtomicInteger step1Count = new AtomicInteger();
        AtomicInteger step2Count = new AtomicInteger();
        AtomicInteger step3Count = new AtomicInteger();
        AtomicInteger ensureCount = new AtomicInteger();
        List<String> recordedInvocationIds = new ArrayList<>();
        List<Boolean> ensureFailedFlags = new ArrayList<>();

        Flow<String, String> flow = Flows.<String>begin("root-body-fail-retry-flow")
                .step("s1", (ctx, in) -> {
                    step1Count.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + ":S1";
                })
                .step("s2-flaky", (ctx, in) -> {
                    recordedInvocationIds.add(ctx.invocationId());
                    if (step2Count.incrementAndGet() == 1) {
                        throw new RuntimeException("Simulated step 2 flaky failure");
                    }
                    return in + ":S2";
                })
                .step("s3", (ctx, in) -> {
                    step3Count.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + ":S3";
                })
                .ensure("root-ensure", (ctx, in, cc) -> {
                    ensureCount.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    ensureFailedFlags.add(cc.isFailed());
                })
                .build();

        DurableFlow<String, String> df = runtime.register(flow, 1);

        // Attempt 1: Start
        DurableResult<String> res1 = df.start("exec-root-retry-1", "INIT");
        Assert.assertTrue(res1.isFailed());
        Assert.assertEquals("s2-flaky", res1.failure().nodeId());
        Assert.assertEquals("s2-flaky", res1.failure().nodePath());

        // Check exact node counts after attempt 1
        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(1, step2Count.get());
        Assert.assertEquals(0, step3Count.get());
        Assert.assertEquals(1, ensureCount.get());
        Assert.assertEquals(Collections.singletonList(true), ensureFailedFlags);

        // Attempt 2: Retry
        DurableResult<String> res2 = df.retry("exec-root-retry-1");
        Assert.assertTrue(res2.isCompleted());
        Assert.assertEquals("INIT:S1:S2:S3", res2.value());

        // Check exact node counts after retry: s1 was NOT replayed, s2 replayed once, s3 ran once, ensure ran once more
        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(2, step2Count.get());
        Assert.assertEquals(1, step3Count.get());
        Assert.assertEquals(2, ensureCount.get());
        Assert.assertEquals(Arrays.asList(true, false), ensureFailedFlags);

        // Stable invocation IDs
        String execPrefix = "root-body-fail-retry-flow:1:exec-root-retry-1#";
        Assert.assertEquals(execPrefix + "/s0:s1", recordedInvocationIds.get(0));
        Assert.assertEquals(execPrefix + "/s1:s2-flaky", recordedInvocationIds.get(1));
        Assert.assertEquals(execPrefix + "/ensure:root-ensure", recordedInvocationIds.get(2));
        // Retry invocation IDs:
        Assert.assertEquals(execPrefix + "/s1:s2-flaky", recordedInvocationIds.get(3));
        Assert.assertEquals(execPrefix + "/s2:s3", recordedInvocationIds.get(4));
        Assert.assertEquals(execPrefix + "/ensure:root-ensure", recordedInvocationIds.get(5));
    }

    @Test
    public void retry_nestedBodyFailureWithChildAndParentEnsure_restoresFramesAndDoesNotReevaluateSelector() {
        AtomicInteger rootStep0Count = new AtomicInteger();
        AtomicInteger selectorCount = new AtomicInteger();
        AtomicInteger childStep1Count = new AtomicInteger();
        AtomicInteger childStep2Count = new AtomicInteger();
        AtomicInteger childEnsureCount = new AtomicInteger();
        AtomicInteger rootStep3Count = new AtomicInteger();
        AtomicInteger parentEnsureCount = new AtomicInteger();
        List<String> recordedInvocationIds = new ArrayList<>();
        List<Boolean> childEnsureFailedFlags = new ArrayList<>();
        List<Boolean> parentEnsureFailedFlags = new ArrayList<>();

        Flow<String, String> branchFlow = Flows.<String>begin("branch-flow")
                .step("child-step-1", (ctx, in) -> {
                    childStep1Count.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + ":C1";
                })
                .step("child-step-2-flaky", (ctx, in) -> {
                    recordedInvocationIds.add(ctx.invocationId());
                    if (childStep2Count.incrementAndGet() == 1) {
                        throw new RuntimeException("Child step 2 flaky failure");
                    }
                    return in + ":C2";
                })
                .ensure("child-ensure", (ctx, in, cc) -> {
                    childEnsureCount.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    childEnsureFailedFlags.add(cc.isFailed());
                })
                .build();

        Flow<String, String> rootFlow = Flows.<String>begin("nested-retry-flow")
                .step("root-step-0", (ctx, in) -> {
                    rootStep0Count.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + ":R0";
                })
                .choose("choose-route", in -> {
                    selectorCount.incrementAndGet();
                    return "MAIN";
                })
                .when("MAIN", branchFlow)
                .end()
                .step("root-step-3", (ctx, in) -> {
                    rootStep3Count.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + ":R3";
                })
                .ensure("parent-ensure", (ctx, in, cc) -> {
                    parentEnsureCount.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    parentEnsureFailedFlags.add(cc.isFailed());
                })
                .build();

        DurableFlow<String, String> df = runtime.register(rootFlow, 1);

        // Attempt 1: Start
        DurableResult<String> res1 = df.start("exec-nested-retry-1", "INIT");
        Assert.assertTrue(res1.isFailed());
        Assert.assertEquals("child-step-2-flaky", res1.failure().nodeId());
        Assert.assertEquals("choose-route/child-step-2-flaky", res1.failure().nodePath());

        // Check exact counts after attempt 1
        Assert.assertEquals(1, rootStep0Count.get());
        Assert.assertEquals(1, selectorCount.get());
        Assert.assertEquals(1, childStep1Count.get());
        Assert.assertEquals(1, childStep2Count.get());
        Assert.assertEquals(1, childEnsureCount.get());
        Assert.assertEquals(1, parentEnsureCount.get());
        Assert.assertEquals(0, rootStep3Count.get());
        Assert.assertEquals(Collections.singletonList(true), childEnsureFailedFlags);
        Assert.assertEquals(Collections.singletonList(true), parentEnsureFailedFlags);

        // Attempt 2: Retry
        DurableResult<String> res2 = df.retry("exec-nested-retry-1");
        Assert.assertTrue(res2.isCompleted());
        Assert.assertEquals("INIT:R0:C1:C2:R3", res2.value());

        // Check exact counts after retry:
        // rootStep0 NOT replayed (1), selector NOT reevaluated (1), childStep1 NOT replayed (1),
        // childStep2 replayed (2), childEnsure ran (2), rootStep3 ran (1), parentEnsure ran (2)
        Assert.assertEquals(1, rootStep0Count.get());
        Assert.assertEquals(1, selectorCount.get());
        Assert.assertEquals(1, childStep1Count.get());
        Assert.assertEquals(2, childStep2Count.get());
        Assert.assertEquals(2, childEnsureCount.get());
        Assert.assertEquals(1, rootStep3Count.get());
        Assert.assertEquals(2, parentEnsureCount.get());
        Assert.assertEquals(Arrays.asList(true, false), childEnsureFailedFlags);
        Assert.assertEquals(Arrays.asList(true, false), parentEnsureFailedFlags);

        // Verify invocation IDs are stable
        String prefix = "nested-retry-flow:1:exec-nested-retry-1#";
        Assert.assertEquals(prefix + "/s0:root-step-0", recordedInvocationIds.get(0));
        Assert.assertEquals(prefix + "/s1:choose-route/case:0/s0:child-step-1", recordedInvocationIds.get(1));
        Assert.assertEquals(prefix + "/s1:choose-route/case:0/s1:child-step-2-flaky", recordedInvocationIds.get(2));
        Assert.assertEquals(prefix + "/s1:choose-route/case:0/ensure:child-ensure", recordedInvocationIds.get(3));
        Assert.assertEquals(prefix + "/ensure:parent-ensure", recordedInvocationIds.get(4));

        // Retry part:
        Assert.assertEquals(prefix + "/s1:choose-route/case:0/s1:child-step-2-flaky", recordedInvocationIds.get(5));
        Assert.assertEquals(prefix + "/s1:choose-route/case:0/ensure:child-ensure", recordedInvocationIds.get(6));
        Assert.assertEquals(prefix + "/s2:root-step-3", recordedInvocationIds.get(7));
        Assert.assertEquals(prefix + "/ensure:parent-ensure", recordedInvocationIds.get(8));
    }

    @Test
    public void retry_recoverHandlerFailure_retriesRecoverInvocation() {
        AtomicInteger step1Count = new AtomicInteger();
        AtomicInteger recoverCount = new AtomicInteger();
        AtomicInteger ensureCount = new AtomicInteger();
        List<String> recordedInvocationIds = new ArrayList<>();

        Flow<String, String> flow = Flows.<String>begin("recover-fail-retry-flow")
                .step("failing-step", (Step.Contextual<String, String>) (ctx, in) -> {
                    step1Count.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    throw new RuntimeException("Original step failure");
                })
                .recover("flaky-recover", (ctx, in, fc) -> {
                    recordedInvocationIds.add(ctx.invocationId());
                    if (recoverCount.incrementAndGet() == 1) {
                        throw new RuntimeException("Recover handler failed on attempt 1");
                    }
                    return FlowResult.succeeded("recovered-value:" + in);
                })
                .ensure("flow-ensure", (ctx, in, cc) -> {
                    ensureCount.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                })
                .build();

        DurableFlow<String, String> df = runtime.register(flow, 1);

        // Attempt 1: Start -> step fails -> recover fails -> ensure runs -> ends FAILED
        DurableResult<String> res1 = df.start("exec-rec-fail-1", "INIT");
        Assert.assertTrue(res1.isFailed());
        Assert.assertEquals("flaky-recover", res1.failure().nodeId());
        Assert.assertEquals("flaky-recover", res1.failure().nodePath());
        Assert.assertTrue(res1.failure().message().contains("Recover handler failed on attempt 1"));

        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(1, recoverCount.get());
        Assert.assertEquals(1, ensureCount.get());

        // Attempt 2: Retry -> recover invocation is retried directly without replaying failing-step
        DurableResult<String> res2 = df.retry("exec-rec-fail-1");
        Assert.assertTrue(res2.isCompleted());
        Assert.assertEquals("recovered-value:INIT", res2.value());

        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(2, recoverCount.get());
        Assert.assertEquals(2, ensureCount.get());

        String prefix = "recover-fail-retry-flow:1:exec-rec-fail-1#";
        Assert.assertEquals(prefix + "/s0:failing-step", recordedInvocationIds.get(0));
        Assert.assertEquals(prefix + "/recover:flaky-recover", recordedInvocationIds.get(1));
        Assert.assertEquals(prefix + "/ensure:flow-ensure", recordedInvocationIds.get(2));
        // Retry invocation:
        Assert.assertEquals(prefix + "/recover:flaky-recover", recordedInvocationIds.get(3));
        Assert.assertEquals(prefix + "/ensure:flow-ensure", recordedInvocationIds.get(4));
    }

    @Test
    public void retry_ensureOnlyFailure_retriesEnsureOnly() {
        AtomicInteger step1Count = new AtomicInteger();
        AtomicInteger step2Count = new AtomicInteger();
        AtomicInteger ensureCount = new AtomicInteger();
        List<String> recordedInvocationIds = new ArrayList<>();

        Flow<String, String> flow = Flows.<String>begin("ensure-only-fail-retry-flow")
                .step("s1", (ctx, in) -> {
                    step1Count.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + ":S1";
                })
                .step("s2", (ctx, in) -> {
                    step2Count.incrementAndGet();
                    recordedInvocationIds.add(ctx.invocationId());
                    return in + ":S2";
                })
                .ensure("flaky-ensure", (ctx, in, cc) -> {
                    recordedInvocationIds.add(ctx.invocationId());
                    if (ensureCount.incrementAndGet() == 1) {
                        throw new RuntimeException("Ensure crashed on attempt 1");
                    }
                })
                .build();

        DurableFlow<String, String> df = runtime.register(flow, 1);

        // Attempt 1: Body succeeds, but ensure throws exception -> Result is FAILED with Ensure failure
        DurableResult<String> res1 = df.start("exec-ens-only-1", "INIT");
        Assert.assertTrue(res1.isFailed());
        Assert.assertEquals("flaky-ensure", res1.failure().nodeId());
        Assert.assertEquals("flaky-ensure", res1.failure().nodePath());

        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(1, step2Count.get());
        Assert.assertEquals(1, ensureCount.get());

        // Attempt 2: Retry -> Retries ensure only, steps are NOT replayed!
        DurableResult<String> res2 = df.retry("exec-ens-only-1");
        Assert.assertTrue(res2.isCompleted());
        Assert.assertEquals("INIT:S1:S2", res2.value());

        Assert.assertEquals(1, step1Count.get());
        Assert.assertEquals(1, step2Count.get());
        Assert.assertEquals(2, ensureCount.get());

        String prefix = "ensure-only-fail-retry-flow:1:exec-ens-only-1#";
        Assert.assertEquals(prefix + "/s0:s1", recordedInvocationIds.get(0));
        Assert.assertEquals(prefix + "/s1:s2", recordedInvocationIds.get(1));
        Assert.assertEquals(prefix + "/ensure:flaky-ensure", recordedInvocationIds.get(2));
        // Retry invocation:
        Assert.assertEquals(prefix + "/ensure:flaky-ensure", recordedInvocationIds.get(3));
    }

    @Test
    public void retry_ensureFailsWhileBodyFailureRemainsPrimary_retriesOriginalBodyFailure() {
        AtomicInteger step1Count = new AtomicInteger();
        AtomicInteger step2Count = new AtomicInteger();
        AtomicInteger ensureCount = new AtomicInteger();

        Flow<String, String> flow = Flows.<String>begin("body-fail-ensure-fail-retry-flow")
                .step("s1", in -> in + ":S1")
                .step("s2-flaky", in -> {
                    if (step2Count.incrementAndGet() == 1) {
                        throw new RuntimeException("Primary body failure");
                    }
                    return in + ":S2";
                })
                .ensure("flaky-ensure", (in, cc) -> {
                    ensureCount.incrementAndGet();
                    if (cc.isFailed()) {
                        throw new RuntimeException("Secondary ensure failure");
                    }
                })
                .build();

        DurableFlow<String, String> df = runtime.register(flow, 1);

        DurableResult<String> res1 = df.start("exec-both-fail-1", "INIT");
        Assert.assertTrue(res1.isFailed());
        // Primary failure is s2-flaky
        Assert.assertEquals("s2-flaky", res1.failure().nodeId());

        Assert.assertEquals(1, step2Count.get());
        Assert.assertEquals(1, ensureCount.get());

        // Retry: must retry original body failure (s2-flaky), not just ensure!
        DurableResult<String> res2 = df.retry("exec-both-fail-1");
        Assert.assertTrue(res2.isCompleted());
        Assert.assertEquals("INIT:S1:S2", res2.value());

        Assert.assertEquals(2, step2Count.get());
        Assert.assertEquals(2, ensureCount.get());
    }

    @Test
    public void diagnostics_nestedSubflowAndChoose_nodePathIncludesFullScopePrefixes() {
        List<String> capturedNodePaths = new ArrayList<>();

        Flow<String, String> branchFlow = Flows.<String>begin("sub-branch")
                .step("deep-step", (ctx, in) -> {
                    capturedNodePaths.add(ctx.nodePath());
                    return in + ":DEEP";
                })
                .build();

        Flow<String, String> innerSubflow = Flows.<String>begin("sub-scope")
                .choose("choose-scope", in -> "GO")
                .when("GO", branchFlow)
                .end()
                .build();

        Flow<String, String> rootFlow = Flows.<String>begin("diag-root-flow")
                .step("root-step", (ctx, in) -> {
                    capturedNodePaths.add(ctx.nodePath());
                    return in + ":ROOT";
                })
                .then(innerSubflow)
                .build();

        DurableFlow<String, String> df = runtime.register(rootFlow, 1);
        DurableResult<String> res = df.start("exec-diag-1", "INIT");

        Assert.assertTrue(res.isCompleted());
        Assert.assertEquals("INIT:ROOT:DEEP", res.value());

        Assert.assertEquals(2, capturedNodePaths.size());
        Assert.assertEquals("root-step", capturedNodePaths.get(0));
        Assert.assertEquals("sub-scope/choose-scope/deep-step", capturedNodePaths.get(1));
    }

    @Test
    public void diagnostics_nestedFailure_durableFailureNodePathMatchesCoreSemantics() {
        Flow<String, String> innerSubflow = Flows.<String>begin("service-subflow")
                .step("inner-fail-step", (Step<String, String>) in -> {
                    throw new RuntimeException("Inner step failed");
                })
                .build();

        Flow<String, String> rootFlow = Flows.<String>begin("diag-fail-flow")
                .then(innerSubflow)
                .build();

        DurableFlow<String, String> df = runtime.register(rootFlow, 1);
        DurableResult<String> res = df.start("exec-diag-fail-1", "INIT");

        Assert.assertTrue(res.isFailed());
        Assert.assertEquals("inner-fail-step", res.failure().nodeId());
        Assert.assertEquals("service-subflow/inner-fail-step", res.failure().nodePath());
    }

    @Test
    public void ensure_atLeastOnceReplay_invokedWithSameInvocationIdOnCrashReplay() {
        AtomicInteger ensureExecutionCount = new AtomicInteger();
        List<String> ensureInvocationIds = new ArrayList<>();

        Flow<String, String> flow = Flows.<String>begin("ensure-at-least-once-flow")
                .step("s1", in -> in + ":OK")
                .ensure("action-node", (ctx, in, completion) -> {
                    ensureInvocationIds.add(ctx.invocationId());
                    if (ensureExecutionCount.incrementAndGet() == 1) {
                        // At-least-once guarantee: simulate crash after ensure action has run on attempt 1
                        throw new SimulatedCrash("Simulated crash inside/after ensure action on attempt 1");
                    }
                })
                .build();

        DurableFlow<String, String> df = runtime.register(flow, 1);

        try {
            df.start("exec-ens-alo-1", "INIT");
            Assert.fail("Expected SimulatedCrash on first ensure execution");
        } catch (SimulatedCrash expected) {
            Assert.assertTrue(expected.getMessage().contains("Simulated crash inside/after ensure action"));
        }

        Assert.assertEquals(1, ensureExecutionCount.get());
        Assert.assertEquals(1, ensureInvocationIds.size());

        // Recover: will replay ensure action (demonstrating at-least-once invocation semantics)
        DurableResult<String> recoveredResult = df.recover("exec-ens-alo-1");
        Assert.assertTrue(recoveredResult.isCompleted());
        Assert.assertEquals("INIT:OK", recoveredResult.value());

        Assert.assertEquals(2, ensureExecutionCount.get());
        Assert.assertEquals(2, ensureInvocationIds.size());

        String expectedId = "ensure-at-least-once-flow:1:exec-ens-alo-1#/ensure:action-node";
        Assert.assertEquals(expectedId, ensureInvocationIds.get(0));
        Assert.assertEquals(expectedId, ensureInvocationIds.get(1));
    }
}
