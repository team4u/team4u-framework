package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;

/** 组8：取消矩阵与异步边界 — 终态/挂起取消、终态命令拒绝、无 executor 异步拒绝、executor 借用不关闭。 */
public class DurableCancelAsyncTest {

    private static final ResumePoint<String> GATE = ResumePoint.named("gate");

    private static DurableExecutable<String, String> activeExecutable(DurableStore store) {
        // 退避等待中的 RETRY 使执行停留在 ACTIVE（wake 远在本测试之后）
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return com.team4u.framework.flow.model.Outcome.failed(
                        com.team4u.framework.flow.model.Failure.of("X", "x"));
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .retry(new com.team4u.framework.flow.api.Retry(5,
                        java.time.Duration.ofMillis(60_000)));
        return DurableRuntime.builder(store).build().compile(flow, "cancel", 1);
    }

    private static DurableExecutable<String, String> compileAwaitFlow(DurableStore store) {
        Flow<String, Resumed<String, String>> flow =
                Flow.<String, String>step(new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, String input) {
                        return Outcome.accepted(input);
                    }
                }).await(GATE);
        return DurableRuntime.builder(store).build()
                .compile(Flow.<String, String>step(
                                new Operation<String, String>() {
                                    @Override
                                    public Outcome<String> execute(OperationContext ctx,
                                                                   String input) {
                                        return Outcome.accepted(input);
                                    }
                                }).<String>await(GATE)
                                .then(new Operation<Resumed<String, String>, String>() {
                                    @Override
                                    public Outcome<String> execute(
                                            OperationContext ctx,
                                            Resumed<String, String> resumed) {
                                        return Outcome.accepted(resumed.signal());
                                    }
                                }),
                        "cancel", 1);
    }

    // ------------------------------------------------------------------
    // cancel
    // ------------------------------------------------------------------

    @Test
    public void cancelActiveExecutionLandsCancelledTerminalState() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = activeExecutable(store);
        DurableResult<String> started = executable.start("e", "in");
        assertTrue(started.getClass().getSimpleName(), started instanceof DurableResult.Active);
        assertEquals(DurableLifecycle.ACTIVE, store.load("e").get().lifecycle());
        DurableResult<String> cancelled = executable.cancel("e");
        assertTrue(cancelled.getClass().getSimpleName(), cancelled instanceof DurableResult.Cancelled);
        assertEquals(DurableLifecycle.CANCELLED, store.load("e").get().lifecycle());
        // revision 递增推进
        assertTrue(store.load("e").get().revision() > started.snapshot().revision());
    }

    @Test
    public void cancelSuspendedExecutionLandsCancelled() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compileAwaitFlow(store);
        DurableResult<String> suspended = executable.start("e", "in");
        assertTrue(suspended.getClass().getSimpleName(),
                suspended instanceof DurableResult.Suspended);
        DurableResult<String> cancelled = executable.cancel("e");
        assertTrue(cancelled instanceof DurableResult.Cancelled);
        assertEquals(DurableLifecycle.CANCELLED, store.load("e").get().lifecycle());
        // CANCELLED 快照不得残留 resume 状态
        DurableSnapshot snapshot = store.load("e").get();
        assertEquals(null, snapshot.awaitingPoint());
        assertFalse(snapshot.pendingResume());
    }

    @Test
    public void cancelCompletedExecutionIsRejected() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store).build()
                .compile(Flow.<String, String>step(
                        new Operation<String, String>() {
                            @Override
                            public Outcome<String> execute(OperationContext ctx, String in) {
                                return Outcome.accepted(in);
                            }
                        }), "cancel", 1);
        executable.start("e", "in");
        try {
            executable.cancel("e");
            fail("COMPLETED 上的 cancel 必须 LIFECYCLE_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.LIFECYCLE_MISMATCH, error.error());
        }
    }

    @Test
    public void cancelCancelledExecutionIsRejected() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = activeExecutable(store);
        executable.start("e", "in");
        executable.cancel("e");
        try {
            executable.cancel("e");
            fail("CANCELLED 上的 cancel 必须 LIFECYCLE_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.LIFECYCLE_MISMATCH, error.error());
        }
    }

    @Test
    public void recoverAfterCancelIsRejected() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = activeExecutable(store);
        executable.start("e", "in");
        executable.cancel("e");
        try {
            executable.recover("e");
            fail("CANCELLED 上的 recover 必须 LIFECYCLE_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.LIFECYCLE_MISMATCH, error.error());
        }
    }

    @Test
    public void resumeAfterCancelIsRejected() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compileAwaitFlow(store);
        executable.start("e", "in");
        executable.cancel("e");
        try {
            executable.resume("e", "gate", "GO");
            fail("CANCELLED 上的 resume 必须 LIFECYCLE_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.LIFECYCLE_MISMATCH, error.error());
        }
    }

    // ------------------------------------------------------------------
    // async
    // ------------------------------------------------------------------

    /** 可检测 shutdown 是否被调用的 ExecutorService stub。 */
    static final class RecordingExecutor extends AbstractExecutorService {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger shutdownCalls = new AtomicInteger();
        volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            executions.incrementAndGet();
            command.run();
        }

        @Override
        public void shutdown() {
            shutdownCalls.incrementAndGet();
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownCalls.incrementAndGet();
            shutdown = true;
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    @Test
    public void startAsyncWithoutExecutorThrowsAsyncExecutorMissing() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store).build()
                .compile(Flow.<String, String>step(
                        new Operation<String, String>() {
                            @Override
                            public Outcome<String> execute(OperationContext ctx, String in) {
                                return Outcome.accepted(in);
                            }
                        }), "cancel", 1);
        try {
            executable.startAsync("e", "in");
            fail("无 executor 时 startAsync 必须 ASYNC_EXECUTOR_MISSING");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.ASYNC_EXECUTOR_MISSING, error.error());
        }
        // 未创建任何执行
        assertFalse(store.load("e").isPresent());
    }

    @Test
    public void resumeAsyncWithoutExecutorThrowsAsyncExecutorMissing() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compileAwaitFlow(store);
        executable.start("e", "in");
        try {
            executable.resumeAsync("e", "gate", "GO");
            fail("无 executor 时 resumeAsync 必须 ASYNC_EXECUTOR_MISSING");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.ASYNC_EXECUTOR_MISSING, error.error());
        }
    }

    @Test
    public void startAsyncRunsOnCallerExecutorAndNeverShutsItDown() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store)
                .executor(executor)
                .build()
                .compile(Flow.<String, String>step(
                        new Operation<String, String>() {
                            @Override
                            public Outcome<String> execute(OperationContext ctx, String in) {
                                return Outcome.accepted(in + ">async");
                            }
                        }), "cancel", 1);
        CompletionStage<DurableResult<String>> stage = executable.startAsync("e", "in");
        DurableResult<String> result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        assertEquals("in>async", ((Outcome.Accepted<String>)
                ((DurableResult.Completed<String>) result).outcome()).value());
        // 借用而非拥有：executor 至少被执行过一次，且从未被运行时关闭
        assertTrue(executor.executions.get() >= 1);
        assertEquals("运行时绝不能关闭调用方 executor", 0, executor.shutdownCalls.get());
        assertFalse(executor.isShutdown());
    }
}
