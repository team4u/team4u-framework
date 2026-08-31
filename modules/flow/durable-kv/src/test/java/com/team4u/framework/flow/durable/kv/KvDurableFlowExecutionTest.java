package com.team4u.framework.flow.durable.kv;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.durable.DurableExecutable;
import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.DurableRuntime;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KvDurableFlowExecutionTest {

    private InMemoryKvStore kvStore;
    private KvDurableStore durableStore;
    private DurableRuntime runtime;

    @Before
    public void setUp() {
        kvStore = new InMemoryKvStore();
        durableStore = new KvDurableStore(kvStore);
        runtime = DurableRuntime.builder(durableStore).build();
    }

    private static Operation<String, String> append(final String suffix) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + suffix);
            }
        };
    }

    @Test
    public void linearFlowStartAndComplete() {
        Flow<String, String> flow = Flow.<String, String>step(append("-step1"))
                .then(append("-step2"))
                .then(append("-step3"));

        DurableExecutable<String, String> executable = runtime.compile(flow, "linear-flow", 1);
        DurableResult<String> result = executable.start("order-101", "init");

        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("init-step1-step2-step3", result.requireAccepted());

        DurableSnapshot snapshot = durableStore.load("order-101").orElse(null);
        assertEquals(DurableLifecycle.COMPLETED, snapshot.lifecycle());
        assertEquals("order-101", snapshot.executionId());
    }

    @Test
    public void suspendAndResumeFlow() {
        ResumePoint<String> approvalPoint = ResumePoint.named("manager_approval");

        Flow<String, String> flow = Flow.<String, String>step(append("-draft"))
                .await(approvalPoint)
                .then(new Operation<Resumed<String, String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, Resumed<String, String> input) {
                        return Outcome.accepted(input.state() + "-approved_by:" + input.signal());
                    }
                });

        DurableExecutable<String, String> executable = runtime.compile(flow, "approval-flow", 1);

        // 1. 启动流程，挂起在 manager_approval
        DurableResult<String> suspendedResult = executable.start("doc-202", "order");
        assertTrue(suspendedResult instanceof DurableResult.Suspended);
        assertEquals("manager_approval", ((DurableResult.Suspended<String>) suspendedResult).resumePoint());

        DurableSnapshot suspendedSnapshot = durableStore.load("doc-202").orElse(null);
        assertEquals(DurableLifecycle.SUSPENDED, suspendedSnapshot.lifecycle());
        assertEquals("manager_approval", suspendedSnapshot.awaitingPoint());

        // 2. 注入信号恢复执行
        DurableResult<String> resumedResult = executable.resume("doc-202", approvalPoint.name(), "Alice");
        assertTrue(resumedResult instanceof DurableResult.Completed);
        assertEquals("order-draft-approved_by:Alice", resumedResult.requireAccepted());

        DurableSnapshot completedSnapshot = durableStore.load("doc-202").orElse(null);
        assertEquals(DurableLifecycle.COMPLETED, completedSnapshot.lifecycle());
    }

    @Test
    public void crashAndRecoverFlow() {
        final java.util.concurrent.atomic.AtomicInteger step1Calls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger step2Calls = new java.util.concurrent.atomic.AtomicInteger();

        Operation<String, String> step1 = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                step1Calls.incrementAndGet();
                return Outcome.accepted(input + "-part1");
            }
        };

        Operation<String, String> step2 = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (step2Calls.incrementAndGet() == 1) {
                    throw new SimulatedCrash("Simulated JVM crash!");
                }
                return Outcome.accepted(input + "-part2");
            }
        };

        Flow<String, String> flow = Flow.<String, String>step(step1).then(step2);

        DurableExecutable<String, String> executable = runtime.compile(flow, "recover-flow", 1);

        // 1. 首次执行在 step2 发生致命崩溃（Error）
        try {
            executable.start("task-303", "start");
            org.junit.Assert.fail("Expected crash exception");
        } catch (SimulatedCrash expected) {
            assertEquals("Simulated JVM crash!", expected.getMessage());
        }

        assertEquals(1, step1Calls.get());
        assertEquals(1, step2Calls.get());

        // 2. 模拟系统重启：使用新的 Runtime（连接同一个 DurableStore）进行断点恢复
        DurableRuntime newRuntime = DurableRuntime.builder(durableStore).build();
        DurableExecutable<String, String> newExecutable = newRuntime.compile(flow, "recover-flow", 1);

        // 3. recover 从 step1 已持久化的检查点续跑
        DurableResult<String> recovered = newExecutable.recover("task-303");
        assertTrue(recovered instanceof DurableResult.Completed);
        assertEquals("start-part1-part2", recovered.requireAccepted());

        // 验证 step1 没有被重复调用（检查点生效），step2 被重放
        assertEquals(1, step1Calls.get());
        assertEquals(2, step2Calls.get());

        DurableSnapshot completedSnapshot = durableStore.load("task-303").orElse(null);
        assertEquals(DurableLifecycle.COMPLETED, completedSnapshot.lifecycle());
    }

    @Test
    public void cancelFlow() {
        ResumePoint<String> waitPoint = ResumePoint.named("callback");

        Flow<String, String> flow = Flow.<String, String>step(append("-init"))
                .await(waitPoint)
                .then(new Operation<Resumed<String, String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, Resumed<String, String> input) {
                        return Outcome.accepted(input.state());
                    }
                });

        DurableExecutable<String, String> executable = runtime.compile(flow, "cancel-flow", 1);
        executable.start("order-404", "order");

        DurableResult<String> cancelResult = executable.cancel("order-404");
        assertTrue(cancelResult instanceof DurableResult.Cancelled);

        DurableSnapshot snapshot = durableStore.load("order-404").orElse(null);
        assertEquals(DurableLifecycle.CANCELLED, snapshot.lifecycle());
    }

    private static class SimulatedCrash extends Error {
        SimulatedCrash(String message) {
            super(message);
        }
    }
}
