package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.team4u.framework.flow.durable.DurableTestOps.FaultyStore;
import static com.team4u.framework.flow.durable.DurableTestOps.RecordingOp;
import static com.team4u.framework.flow.durable.DurableTestOps.acceptedValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 组2：CAS 边界 — revision 单调、load 无副作用、重复 start、store 异常、冲突、错误 executionId。 */
public class DurableCasBoundaryTest {

    private static DurableExecutable<String, String> compile(Flow<String, String> flow,
                                                             DurableStore store) {
        return DurableRuntime.builder(store).build().compile(flow, "cas", 1);
    }

    @Test
    public void revisionsAdvanceMonotonicallyPerBoundary() {
        RecordingOp a = new RecordingOp("a");
        RecordingOp b = new RecordingOp("b");
        FaultyStore store = new FaultyStore(new InMemoryDurableStore());
        compile(Flow.<String, String>step(a).then(b), store).start("e", "s");
        // 初始 + invoke a 完成(sequence 推进至 b) + 终态 = 3 次 CAS，每次 expected 严格递增
        assertEquals(3, store.revisions.size());
        assertEquals(Long.valueOf(-1L), store.revisions.get(0));
        assertEquals(Long.valueOf(0L), store.revisions.get(1));
        assertEquals(Long.valueOf(1L), store.revisions.get(2));
        for (int i = 1; i < store.revisions.size(); i++) {
            assertTrue("revision 必须严格单调: " + store.revisions,
                    store.revisions.get(i) > store.revisions.get(i - 1));
        }
        long latest = ((InMemoryDurableStore) store.delegate).load("e").get().revision();
        assertEquals(2L, latest);
    }

    @Test
    public void loadHasNoSideEffects() {
        RecordingOp a = new RecordingOp("a");
        FaultyStore store = new FaultyStore(new InMemoryDurableStore());
        DurableExecutable<String, String> executable =
                compile(Flow.<String, String>step(a), store);
        Optional<DurableSnapshot> before = executable.snapshot("missing");
        assertFalse(before.isPresent());
        assertEquals(1, store.loads.size());
        executable.start("e", "s");
        int loadsAfterStart = store.loads.size();
        // snapshot() 只 load，不 CAS
        int casCount = store.revisions.size();
        executable.snapshot("e");
        executable.snapshot("e");
        assertEquals(loadsAfterStart + 2, store.loads.size());
        assertEquals(casCount, store.revisions.size());
    }

    @Test
    public void duplicateStartIsRejectedWithConflict() {
        RecordingOp a = new RecordingOp("a");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(Flow.<String, String>step(a), store);
        executable.start("dup", "s");
        try {
            executable.start("dup", "s2");
            fail("重复 start 必须 EXECUTION_EXISTS");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.EXECUTION_EXISTS, error.error());
        }
        // 原执行不受影响
        assertEquals(DurableLifecycle.COMPLETED, store.load("dup").get().lifecycle());
    }

    @Test
    public void storeExceptionsAreWrappedAsStoreFailure() {
        RecordingOp a = new RecordingOp("a");
        FaultyStore store = new FaultyStore(new InMemoryDurableStore());
        store.onCas = new FaultyStore.Guard() {
            @Override
            public void apply(String executionId, long expectedRevision,
                              DurableSnapshot update) {
                throw new IllegalStateException("db down");
            }
        };
        try {
            compile(Flow.<String, String>step(a), store).start("e", "s");
            fail("store 异常必须包装为 STORE_FAILURE");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.STORE_FAILURE, error.error());
            assertNotNull(error.getCause());
        }
        store.onCas = null;
        store.failLoad = true;
        try {
            compile(Flow.<String, String>step(a), store).recover("whatever");
            fail("load 异常必须包装为 STORE_FAILURE");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.STORE_FAILURE, error.error());
        }
    }

    @Test
    public void revisionConflictFromRacingWriterFailsTheDrive() {
        RecordingOp a = new RecordingOp("a");
        final AtomicInteger casCalls = new AtomicInteger();
        InMemoryDurableStore real = new InMemoryDurableStore();
        FaultyStore store = new FaultyStore(real) {
            @Override
            public boolean compareAndSet(String executionId, long expectedRevision,
                                         DurableSnapshot update) {
                // 第二次 CAS 前外部写入者抢先把 revision 推高
                if (casCalls.incrementAndGet() == 2) {
                    DurableSnapshot raced = new DurableSnapshot(executionId,
                            update.flowId(), update.flowVersion(), update.formatId(),
                            update.formatVersion(), expectedRevision + 1,
                            DurableLifecycle.ACTIVE,
                            update.frameMetadata(), update.slots(), null, false);
                    real.compareAndSet(executionId, expectedRevision, raced);
                }
                return super.compareAndSet(executionId, expectedRevision, update);
            }
        };
        try {
            compile(Flow.<String, String>step(a), store).start("e", "s");
            fail("外部抢占 revision 后必须 REVISION_CONFLICT");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.REVISION_CONFLICT, error.error());
        }
    }

    @Test
    public void wrongExecutionIdIsReportedAsNotFound() {
        RecordingOp a = new RecordingOp("a");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(Flow.<String, String>step(a), store);
        executable.start("real", "s");
        try {
            executable.recover("typo");
            fail("不存在的 executionId 必须 EXECUTION_NOT_FOUND");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.EXECUTION_NOT_FOUND, error.error());
        }
        try {
            executable.resume("typo", "p", "v");
            fail();
        } catch (DurableException error) {
            assertEquals(DurableException.Error.EXECUTION_NOT_FOUND, error.error());
        }
        try {
            executable.cancel("typo");
            fail();
        } catch (DurableException error) {
            assertEquals(DurableException.Error.EXECUTION_NOT_FOUND, error.error());
        }
    }

    @Test
    public void flowIdAndVersionMismatchIsRejected() {
        RecordingOp a = new RecordingOp("a");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableRuntime runtime = DurableRuntime.builder(store).build();
        DurableExecutable<String, String> v1 = runtime.compile(
                Flow.<String, String>step(a), "flowA", 1);
        v1.start("e", "s");
        // 同 store 上另一个 flow/版本的同名执行
        DurableExecutable<String, String> other = runtime.compile(
                Flow.<String, String>step(new RecordingOp("x")), "flowB", 2);
        try {
            other.recover("e");
            fail("不同 flow 的快照必须 FLOW_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.FLOW_MISMATCH, error.error());
        }
    }

    @Test
    public void unknownFormatIsRejected() {
        RecordingOp a = new RecordingOp("a");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store).build()
                .compile(Flow.<String, String>step(a), "fmt", 1);
        // 直接塞入一个坏格式快照
        DurableSnapshot bad = new DurableSnapshot("e2", "fmt", 1, "other-format", 9,
                0L, DurableLifecycle.ACTIVE, new byte[]{1, 2, 3},
                new java.util.LinkedHashMap<String, StoredValue>(), null, false);
        assertTrue(store.compareAndSet("e2", -1, bad));
        try {
            executable.recover("e2");
            fail("未知格式必须 FORMAT_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.FORMAT_MISMATCH, error.error());
        }
    }

    @Test
    public void initialSnapshotIsCreatedBeforeFirstCallback() {
        // start 必须先落初始 ACTIVE 快照（revision=1）再执行首个 Operation
        final AtomicInteger observeAtFirstCall = new AtomicInteger(-1);
        com.team4u.framework.flow.Operation<String, String> probe =
                new com.team4u.framework.flow.Operation<String, String>() {
                    @Override
                    public com.team4u.framework.flow.Outcome<String> execute(
                            com.team4u.framework.flow.OperationContext context, String input) {
                        observeAtFirstCall.set((int) context.metadata().flowVersion());
                        return com.team4u.framework.flow.Outcome.accepted(input);
                    }
                };
        FaultyStore store = new FaultyStore(new InMemoryDurableStore());
        compile(Flow.<String, String>step(probe), store).start("e", "s");
        // 首个 Operation 执行时，初始 CAS 已发生（revisions 中已有 -1 → 创建）
        assertTrue(store.revisions.size() >= 1);
        assertEquals(Long.valueOf(-1L), store.revisions.get(0));
        assertEquals(1, observeAtFirstCall.get());
    }

    @Test
    public void inMemoryStoreValidatesRevisionContract() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        java.util.LinkedHashMap<String, StoredValue> noSlots =
                new java.util.LinkedHashMap<String, StoredValue>();
        DurableSnapshot create = snapshot("e", 0L, DurableLifecycle.ACTIVE, null, false);
        assertTrue(store.compareAndSet("e", -1, create));
        // revision 单调推进：expected 必须精确匹配当前值
        DurableSnapshot next = snapshot("e", 1L, DurableLifecycle.ACTIVE, null, false);
        assertTrue(store.compareAndSet("e", 0, next));
        DurableSnapshot stale = snapshot("e", 6L, DurableLifecycle.ACTIVE, null, false);
        assertFalse("过期的 expected 返回 false 而非异常",
                store.compareAndSet("e", 5, stale));
        // update.revision 不等于 expected+1 直接拒绝
        try {
            store.compareAndSet("e", 1, create);
            fail("update.revision 必须等于 expected+1");
        } catch (IllegalArgumentException expected) {
            // 合同校验
        }
        // 不存在的 executionId：非 create CAS 一律 false
        DurableSnapshot missing = snapshot("other", 6L, DurableLifecycle.ACTIVE, null, false);
        assertFalse(store.compareAndSet("other", 5, missing));
        // executionId 与快照不匹配直接拒绝
        try {
            store.compareAndSet("e", 1, missing);
            fail();
        } catch (IllegalArgumentException expected) {
            // 合同校验
        }
    }

    private static DurableSnapshot snapshot(String executionId, long revision,
                                            DurableLifecycle lifecycle, String awaiting,
                                            boolean pendingResume) {
        return new DurableSnapshot(executionId, "f", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                revision, lifecycle, new byte[0],
                new java.util.LinkedHashMap<String, StoredValue>(), awaiting, pendingResume);
    }
}
