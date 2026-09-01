package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;

public class DurableRecoverSmokeTest {

    /** 第 n 次被调用时抛 Error 模拟崩溃。 */
    static final class CrashOp implements Operation<String, String> {
        final AtomicInteger calls = new AtomicInteger();
        final int crashAt;

        CrashOp(int crashAt) {
            this.crashAt = crashAt;
        }

        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            if (calls.incrementAndGet() == crashAt) {
                throw new AssertionError("SIMULATED_CRASH");
            }
            return Outcome.accepted(input + "+" + context.invocationId().split(":")[3]);
        }
    }

    @Test
    public void crashBeforeCheckpointReplaysSameInvocationId() {
        // 3 步 sequence；第 2 步首次执行时崩溃（检查点前），recover 重放且 invocationId 稳定
        CrashOp a = new CrashOp(Integer.MAX_VALUE);
        CrashOp b = new CrashOp(1);
        CrashOp c = new CrashOp(Integer.MAX_VALUE);
        Flow<String, String> flow = Flow.<String, String>step(a).then(b).then(c);
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = Durable.builder(store)
                .build().compile(flow, "cf", 1);
        try {
            executable.start("e1", "x");
        } catch (AssertionError crash) {
            assertEquals("SIMULATED_CRASH", crash.getMessage());
        }
        // 崩溃后存储里是最后一次成功检查点
        DurableSnapshot snapshot = store.load("e1").get();
        assertEquals(DurableLifecycle.ACTIVE, snapshot.lifecycle());
        // b 尚未被提交过输出
        DurableResult<String> recovered = executable.recover("e1");
        assertTrue(recovered.getClass().getSimpleName(),
                recovered instanceof DurableResult.Completed);
        assertEquals(2, b.calls.get());
        assertEquals("x+$/0+$/1+$/2", ((com.team4u.framework.flow.model.Outcome.Accepted<String>) ((DurableResult.Completed<String>) recovered).outcome()).value());
    }

    @Test
    public void awaitSuspendsAndResumeCompletes() {
        final ResumePoint<String> point = ResumePoint.named("approval");
        Flow<String, Resumed<String, String>> flow = Flow.<String, String>step(
                new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, String input) {
                        return Outcome.accepted(input + "-pre");
                    }
                }).await(point);
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, Resumed<String, String>> executable =
                Durable.builder(store).build().compile(flow, "aw", 1);
        DurableResult<Resumed<String, String>> suspended = executable.start("e2", "x");
        assertTrue(suspended.getClass().getSimpleName(),
                suspended instanceof DurableResult.Suspended);
        assertEquals("approval", ((DurableResult.Suspended<Resumed<String, String>>) suspended)
                .resumePoint());
        assertEquals(DurableLifecycle.SUSPENDED, store.load("e2").get().lifecycle());
        DurableResult<Resumed<String, String>> resumed =
                executable.resume("e2", "approval", "GO");
        assertTrue(resumed.getClass().getSimpleName(),
                resumed instanceof DurableResult.Completed);
        Resumed<String, String> value = ((com.team4u.framework.flow.model.Outcome.Accepted<Resumed<String, String>>) ((DurableResult.Completed<Resumed<String, String>>) resumed)
                .outcome()).value();
        assertEquals("x-pre", value.state());
        assertEquals("GO", value.signal());
        assertEquals(DurableLifecycle.COMPLETED, store.load("e2").get().lifecycle());
    }

    @Test
    public void awaitThenContinueAfterResume() {
        final ResumePoint<String> point = ResumePoint.named("wait");
        Operation<Resumed<String, String>, String> after = new Operation<Resumed<String, String>, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, Resumed<String, String> input) {
                return Outcome.accepted(input.state() + ":" + input.signal());
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(
                new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, String input) {
                        return Outcome.accepted("s-" + input);
                    }
                }).await(point).then(after);
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = Durable.builder(store).build()
                .compile(flow, "aw2", 1);
        DurableResult<String> suspended = executable.start("e3", "in");
        assertTrue(suspended instanceof DurableResult.Suspended);
        DurableResult<String> resumed = executable.resume("e3", "wait", "sig");
        assertTrue(resumed.getClass().getName(), resumed instanceof DurableResult.Completed);
        assertEquals("s-in:sig", ((com.team4u.framework.flow.model.Outcome.Accepted<String>) ((DurableResult.Completed<String>) resumed).outcome()).value());
    }
}
