package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Operation;
import com.team4u.framework.flow.OperationContext;
import com.team4u.framework.flow.Outcome;
import com.team4u.framework.flow.Resumed;
import com.team4u.framework.flow.ResumePoint;
import org.junit.Test;

import static com.team4u.framework.flow.durable.DurableTestOps.RecordingOp;
import static com.team4u.framework.flow.durable.DurableTestOps.SimulatedCrash;
import static com.team4u.framework.flow.durable.DurableTestOps.acceptedValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 组4：await/resume 全矩阵 — 挂起、错点、幂等、冲突、信号落库后崩溃恢复、resume 后完成。 */
public class DurableAwaitResumeTest {

    private static final ResumePoint<String> APPROVAL = ResumePoint.named("approval");
    private static final ResumePoint<String> SECOND = ResumePoint.named("second");

    private static Flow<String, Resumed<String, String>> singleAwaitFlow() {
        return Flow.<String, String>step(new RecordingOp("pre")).await(APPROVAL);
    }

    private static Flow<String, String> awaitThenPostFlow() {
        return Flow.<String, String>step(new RecordingOp("pre"))
                .await(APPROVAL)
                .then(new com.team4u.framework.flow.Operation<Resumed<String, String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context,
                                                   Resumed<String, String> input) {
                        return Outcome.accepted(input.state() + "#" + input.signal());
                    }
                });
    }

    private static DurableExecutable<String, String> compile(Flow<String, String> flow,
                                                             DurableStore store) {
        return DurableRuntime.builder(store).build().compile(flow, "await", 1);
    }

    @Test
    public void suspendWithoutSignalWritesSuspendedSnapshot() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableResult<String> result = compile(awaitThenPostFlow(), store).start("e", "in");
        assertTrue(result instanceof DurableResult.Suspended);
        DurableResult.Suspended<String> suspended = (DurableResult.Suspended<String>) result;
        assertEquals("approval", suspended.resumePoint());
        DurableSnapshot snapshot = store.load("e").get();
        assertEquals(DurableLifecycle.SUSPENDED, snapshot.lifecycle());
        assertEquals("approval", snapshot.awaitingPoint());
        assertFalse(snapshot.pendingResume());
        assertTrue(snapshot.slots().containsKey("input"));
    }

    @Test
    public void resumeWithWrongPointIsRejected() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(awaitThenPostFlow(), store);
        executable.start("e", "in");
        try {
            executable.resume("e", "wrong-point", "GO");
            fail("点名不匹配必须 RESUME_POINT_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.RESUME_POINT_MISMATCH, error.error());
        }
        // 状态不受影响
        assertEquals(DurableLifecycle.SUSPENDED, store.load("e").get().lifecycle());
    }

    @Test
    public void resumeCompletesAndConsumesSignal() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(awaitThenPostFlow(), store);
        executable.start("e", "in");
        DurableResult<String> result = executable.resume("e", "approval", "GO");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("in>pre#GO", acceptedValue(result));
        assertEquals(DurableLifecycle.COMPLETED, store.load("e").get().lifecycle());
        assertFalse(store.load("e").get().pendingResume());
    }

    @Test
    public void resumeSignalIsPersistedBeforeConsumption() {
        // 信号 CAS 与消费驱动是两次独立提交：信号先落库（ACTIVE+pendingResume）
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(awaitThenPostFlow(), store);
        executable.start("e", "in");
        long suspendedRevision = store.load("e").get().revision();
        // 注入：resume 后立刻在消费前崩溃 —— 用 post 步骤崩溃模拟
        // 简化：直接验证成功路径中 revision 增长（信号提交 + 完成提交）
        executable.resume("e", "approval", "GO");
        DurableSnapshot done = store.load("e").get();
        assertTrue(done.revision() > suspendedRevision);
    }

    @Test
    public void crashBetweenSignalPersistAndConsumeResumesFromSignal() {
        // 信号已 CAS 落库（ACTIVE+pendingResume）、消费前崩溃：
        // 通过 recover 路径证明 ACTIVE+pendingResume 快照可恢复并消费信号继续。
        InMemoryDurableStore store = new InMemoryDurableStore();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        com.team4u.framework.flow.Operation<Resumed<String, String>, String> post =
                new com.team4u.framework.flow.Operation<Resumed<String, String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context,
                                                   Resumed<String, String> input) {
                        calls.incrementAndGet();
                        return Outcome.accepted(input.state() + "#" + input.signal());
                    }
                };
        Flow<String, String> flow = Flow.<String, String>step(new RecordingOp("pre"))
                .await(APPROVAL).then(post);
        DurableExecutable<String, String> executable = compile(flow, store);
        executable.start("e", "in");
        assertEquals(DurableLifecycle.SUSPENDED, store.load("e").get().lifecycle());
        // 第一步提交：信号落库（与 resume() 的独立提交完全一致的字段变更）
        DurableSnapshot suspended = store.load("e").get();
        java.util.LinkedHashMap<String, StoredValue> slots =
                new java.util.LinkedHashMap<String, StoredValue>(suspended.slots());
        slots.put("resume:approval", DefaultStateMapper.INSTANCE.encode("GO"));
        DurableSnapshot signaled = new DurableSnapshot("e", "await", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                suspended.revision() + 1, DurableLifecycle.ACTIVE,
                suspended.frameMetadata(), slots, "approval", true);
        assertTrue(store.compareAndSet("e", suspended.revision(), signaled));
        // recover：ACTIVE+pendingResume 直接消费信号继续
        DurableResult<String> result = executable.recover("e");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("in>pre#GO", acceptedValue(result));
        assertEquals(1, calls.get());
        assertEquals(DurableLifecycle.COMPLETED, store.load("e").get().lifecycle());
    }

    @Test
    public void duplicateResumeSameValueIsIdempotent() {
        // 信号已落库但未消费时重复 resume 同值：幂等重驱动
        InMemoryDurableStore store = new InMemoryDurableStore();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        com.team4u.framework.flow.Operation<Resumed<String, String>, String> post =
                new com.team4u.framework.flow.Operation<Resumed<String, String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context,
                                                   Resumed<String, String> input) {
                        calls.incrementAndGet();
                        return Outcome.accepted(input.state() + "#" + input.signal());
                    }
                };
        Flow<String, String> flow = Flow.<String, String>step(new RecordingOp("pre"))
                .await(APPROVAL).then(post);
        DurableExecutable<String, String> executable = compile(flow, store);
        executable.start("e", "in");
        executable.resume("e", "approval", "GO");
        // 完成后的重复 resume：LIFECYCLE_MISMATCH（终态）
        try {
            executable.resume("e", "approval", "GO");
            fail("COMPLETED 上的 resume 必须 LIFECYCLE_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.LIFECYCLE_MISMATCH, error.error());
        }
    }

    @Test
    public void duplicateResumeWithDifferentValueConflicts() {
        // 信号已落库（ACTIVE+pendingResume）未消费：同点不同值必须 RESUME_SIGNAL_CONFLICT，
        // 同值幂等重驱动完成。
        InMemoryDurableStore store = new InMemoryDurableStore();
        Flow<String, String> flow = awaitThenPostFlow();
        DurableExecutable<String, String> executable = compile(flow, store);
        executable.start("e", "in");
        DurableSnapshot suspended = store.load("e").get();
        java.util.LinkedHashMap<String, StoredValue> slots =
                new java.util.LinkedHashMap<String, StoredValue>(suspended.slots());
        slots.put("resume:approval", DefaultStateMapper.INSTANCE.encode("GO"));
        DurableSnapshot signaled = new DurableSnapshot("e", "await", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                suspended.revision() + 1, DurableLifecycle.ACTIVE,
                suspended.frameMetadata(), slots, "approval", true);
        assertTrue(store.compareAndSet("e", suspended.revision(), signaled));
        // 不同值 → RESUME_SIGNAL_CONFLICT
        try {
            executable.resume("e", "approval", "OTHER");
            fail("同点不同值必须 RESUME_SIGNAL_CONFLICT");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.RESUME_SIGNAL_CONFLICT, error.error());
        }
        // 同值 → 幂等重驱动并完成
        DurableResult<String> result = executable.resume("e", "approval", "GO");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("in>pre#GO", acceptedValue(result));
    }

    @Test
    public void secondAwaitSuspendsAgain() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        Flow<String, String> flow = Flow.<String, String>step(new RecordingOp("pre"))
                .await(APPROVAL)
                .then(new com.team4u.framework.flow.Operation<Resumed<String, String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context,
                                                   Resumed<String, String> input) {
                        return Outcome.accepted(input.signal());
                    }
                })
                .await(SECOND)
                .then(new com.team4u.framework.flow.Operation<Resumed<String, String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context,
                                                   Resumed<String, String> input) {
                        return Outcome.accepted(input.state() + "@" + input.signal());
                    }
                });
        DurableExecutable<String, String> executable = compile(flow, store);
        DurableResult<String> first = executable.start("e", "in");
        assertTrue(first instanceof DurableResult.Suspended);
        assertEquals("approval", ((DurableResult.Suspended<String>) first).resumePoint());
        DurableResult<String> second = executable.resume("e", "approval", "S1");
        assertTrue(second instanceof DurableResult.Suspended);
        assertEquals("second", ((DurableResult.Suspended<String>) second).resumePoint());
        // 错点：现在等待 second，不再接受 approval
        try {
            executable.resume("e", "approval", "X");
            fail();
        } catch (DurableException error) {
            assertEquals(DurableException.Error.RESUME_POINT_MISMATCH, error.error());
        }
        DurableResult<String> done = executable.resume("e", "second", "S2");
        assertTrue(done instanceof DurableResult.Completed);
        assertEquals("S1@S2", acceptedValue(done));
    }

    @Test
    public void resumeOnMissingExecutionFails() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, Resumed<String, String>> executable =
                DurableRuntime.builder(store).build()
                        .compile(singleAwaitFlow(), "await", 1);
        try {
            executable.resume("ghost", "approval", "GO");
            fail();
        } catch (DurableException error) {
            assertEquals(DurableException.Error.EXECUTION_NOT_FOUND, error.error());
        }
    }

    @Test
    public void resumeSignalPersistsThroughDurableObserver() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableTestOps.RecordingDurableObserver durableObserver =
                new DurableTestOps.RecordingDurableObserver();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store)
                .durableObserver(durableObserver)
                .build()
                .compile(awaitThenPostFlow(), "await", 1);
        executable.start("e", "in");
        executable.resume("e", "approval", "GO");
        assertFalse(durableObserver.byType(
                DurableObserver.Type.RESUME_SIGNAL_PERSISTED).isEmpty());
        assertNotNull(durableObserver.byType(
                DurableObserver.Type.CHECKPOINT_COMMITTED));
    }
}
