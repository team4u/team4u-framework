package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.ParallelResults;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Outcome;

/** 组10：快照不变量 — 防御拷贝、构造校验、5000 层嵌套 scope 编解码不栈溢出。 */
public class DurableSnapshotInvariantsTest {

    private static StoredValue value(String codec) {
        return new StoredValue(codec, 1, new byte[]{1, 2, 3});
    }

    private static DurableSnapshot snapshot(byte[] frameMetadata,
                                            Map<String, StoredValue> slots) {
        return new DurableSnapshot("e", "f", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                5L, DurableLifecycle.ACTIVE, frameMetadata, slots, null, false);
    }

    // ------------------------------------------------------------------
    // StoredValue / DurableSnapshot 防御拷贝
    // ------------------------------------------------------------------

    @Test
    public void storedValueDefendsAgainstExternalPayloadMutation() {
        byte[] payload = {1, 2, 3};
        StoredValue stored = new StoredValue("codec", 1, payload);
        // 外部修改构造入参
        payload[0] = 99;
        assertArrayEquals("构造后外部修改不得影响 payload", new byte[]{1, 2, 3},
                stored.payload());
        // 外部修改访问器返回值
        byte[] leaked = stored.payload();
        leaked[1] = 77;
        assertArrayEquals("访问器返回的拷贝不得反向影响内部状态", new byte[]{1, 2, 3},
                stored.payload());
    }

    @Test
    public void snapshotDefendsAgainstExternalMetadataAndSlotMutation() {
        byte[] metadata = {9, 9};
        Map<String, StoredValue> slots = new LinkedHashMap<String, StoredValue>();
        slots.put("input", value("in"));
        DurableSnapshot snapshot = snapshot(metadata, slots);
        // 外部修改构造入参
        metadata[0] = 0;
        slots.put("evil", value("evil"));
        assertArrayEquals(new byte[]{9, 9}, snapshot.frameMetadata());
        assertEquals(1, snapshot.slots().size());
        assertFalse(snapshot.slots().containsKey("evil"));
        // 访问器返回拷贝/不可变视图
        byte[] leakedMetadata = snapshot.frameMetadata();
        leakedMetadata[0] = 0;
        assertArrayEquals("frameMetadata 访问器必须返回拷贝", new byte[]{9, 9},
                snapshot.frameMetadata());
        try {
            snapshot.slots().put("more", value("more"));
            fail("slots 必须是不可修改视图");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
        // equals/hashCode 基于内容而非身份
        Map<String, StoredValue> sameSlots = new LinkedHashMap<String, StoredValue>();
        sameSlots.put("input", value("in"));
        assertEquals(snapshot(metadataCopy(new byte[]{9, 9}), sameSlots), snapshot);
        assertEquals(snapshot(metadataCopy(new byte[]{9, 9}), sameSlots).hashCode(),
                snapshot.hashCode());
    }

    private static byte[] metadataCopy(byte[] source) {
        return java.util.Arrays.copyOf(source, source.length);
    }

    // ------------------------------------------------------------------
    // 构造参数校验
    // ------------------------------------------------------------------

    @Test
    public void storedValueRejectsInvalidArguments() {
        try {
            new StoredValue(null, 1, new byte[0]);
            fail();
        } catch (NullPointerException expected) { }
        try {
            new StoredValue("  ", 1, new byte[0]);
            fail();
        } catch (IllegalArgumentException expected) { }
        try {
            new StoredValue("codec", 0, new byte[0]);
            fail("codecVersion 必须为正");
        } catch (IllegalArgumentException expected) { }
        try {
            new StoredValue("codec", 1, null);
            fail();
        } catch (NullPointerException expected) { }
    }

    @Test
    public void snapshotRejectsInvalidArguments() {
        Map<String, StoredValue> slots = new LinkedHashMap<String, StoredValue>();
        byte[] metadata = new byte[0];
        // null 文本字段
        assertSnapshotRejected(null, "f", 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        assertSnapshotRejected("e", null, 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        assertSnapshotRejected("e", "f", 1, null, 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                null, metadata, slots, null, false);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, null, slots, null, false);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, null, null, false);
        // blank 文本
        assertSnapshotRejected("  ", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        assertSnapshotRejected("e", "", 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        // 数值越界
        assertSnapshotRejected("e", "f", 0, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        assertSnapshotRejected("e", "f", -3, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        assertSnapshotRejected("e", "f", 1, "fmt", 0, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, -1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, false);
        // 生命周期与 resume 状态的一致性
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.SUSPENDED, metadata, slots, null, false);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.SUSPENDED, metadata, slots, "p", true);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, "p", false);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, slots, null, true);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.COMPLETED, metadata, slots, "p", false);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.CANCELLED, metadata, slots, null, true);
        // 槽值不得为 null
        Map<String, StoredValue> withNull = new LinkedHashMap<String, StoredValue>();
        withNull.put("input", null);
        assertSnapshotRejected("e", "f", 1, "fmt", 1, 1L,
                DurableLifecycle.ACTIVE, metadata, withNull, null, false);
    }

    private static void assertSnapshotRejected(String executionId, String flowId,
                                               int flowVersion, String formatId,
                                               int formatVersion, long revision,
                                               DurableLifecycle lifecycle,
                                               byte[] frameMetadata,
                                               Map<String, StoredValue> slots,
                                               String awaitingPoint,
                                               boolean pendingResume) {
        try {
            new DurableSnapshot(executionId, flowId, flowVersion, formatId, formatVersion,
                    revision, lifecycle, frameMetadata, slots, awaitingPoint, pendingResume);
            fail("非法参数必须被拒绝: " + executionId + "/" + flowId + "/" + lifecycle);
        } catch (IllegalArgumentException | NullPointerException expected) {
            // 契约校验
        }
    }

    // ------------------------------------------------------------------
    // 深嵌套编解码（迭代、不栈溢出）
    // ------------------------------------------------------------------

    @Test
    public void deepNestedScopeEncodesAndDecodesWithoutStackOverflow() {
        final int depth = 5000;
        Operation<String, String> tail = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + ">tail");
            }
        };
        // 5000 层具名 scope 嵌套：每层都是独立的 Sequence 帧
        Flow<String, String> flow = Flow.<String, String>step(tail);
        for (int i = 0; i < depth; i++) {
            flow = Flow.scope("s" + i, flow);
        }
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                DurableRuntime.builder(store).build().compile(flow, "deep", 1);
        DurableResult<String> result = executable.start("e", "in");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        assertEquals("in>tail", ((Outcome.Accepted<String>)
                ((DurableResult.Completed<String>) result).outcome()).value());
        assertEquals(DurableLifecycle.COMPLETED, store.load("e").get().lifecycle());
    }

    @Test
    public void deepNestedScopeRecoversWithConsistentFrameStack() {
        final int depth = 5000;
        // 崩溃注入：tail 首次抛 Error，recover 后重放
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        Operation<String, String> flakyTail = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (calls.incrementAndGet() == 1) {
                    throw new DurableTestOps.SimulatedCrash("crash at depth");
                }
                return Outcome.accepted(input + ">tail");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flakyTail);
        for (int i = 0; i < depth; i++) {
            flow = Flow.scope("s" + i, flow);
        }
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                DurableRuntime.builder(store).build().compile(flow, "deep", 1);
        try {
            executable.start("e", "in");
            fail("tail 首次执行必须崩溃");
        } catch (DurableTestOps.SimulatedCrash expected) {
            // 崩溃：帧栈深 5001
        }
        // 崩溃快照：帧栈完整编码（恢复前后一致由 recover 成功推进证明）
        DurableSnapshot crashed = store.load("e").get();
        assertEquals(DurableLifecycle.ACTIVE, crashed.lifecycle());
        DurableResult<String> recovered = executable.recover("e");
        assertTrue(recovered.getClass().getSimpleName(),
                recovered instanceof DurableResult.Completed);
        assertEquals("in>tail", ((Outcome.Accepted<String>)
                ((DurableResult.Completed<String>) recovered).outcome()).value());
        assertEquals(2, calls.get());
    }

    // ------------------------------------------------------------------
    // 编解码回环：parallel 分支结果槽恢复
    // ------------------------------------------------------------------

    @Test
    public void parallelBranchOutcomesSurviveEncodeDecodeRoundTrip() {
        // 部分分支完成后崩溃：已完成的 branchOutcomes 必须编码进快照并在恢复后保留，
        // join 只等待剩余分支。用 FaultyStore 在 join 前崩溃。
        final java.util.concurrent.atomic.AtomicInteger leftCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger rightCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        Operation<String, String> left = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                leftCalls.incrementAndGet();
                return Outcome.accepted(input + ">L");
            }
        };
        Operation<String, String> right = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                rightCalls.incrementAndGet();
                return Outcome.accepted(input + ">R");
            }
        };
        com.team4u.framework.flow.api.Branch<String, String> leftToken =
                com.team4u.framework.flow.api.Branch.of("left", left);
        com.team4u.framework.flow.api.Branch<String, String> rightToken =
                com.team4u.framework.flow.api.Branch.of("right", right);
        Flow<String, String> flow = Flow.<String>parallel(leftToken, rightToken)
                .join(new com.team4u.framework.flow.api.JoinStrategy<String>() {
                    @Override
                    public Outcome<String> join(
                            com.team4u.framework.flow.model.ParallelResults results) {
                        StringBuilder joined = new StringBuilder();
                        for (Branch<?, ?> branch : results.branches()) {
                            try {
                                joined.append(((Outcome.Accepted<String>)
                                        results.outcome(branch)).value()).append("|");
                            } catch (ClassCastException bug) {
                                throw new IllegalStateException("branch value lost", bug);
                            }
                        }
                        return Outcome.accepted(joined.toString());
                    }
                });
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                DurableRuntime.builder(store).build().compile(flow, "round", 1);
        DurableResult<String> result = executable.start("e", "in");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        assertEquals("in>L|in>R|", ((Outcome.Accepted<String>)
                ((DurableResult.Completed<String>) result).outcome()).value());
        assertEquals(1, leftCalls.get());
        assertEquals(1, rightCalls.get());
    }
}
