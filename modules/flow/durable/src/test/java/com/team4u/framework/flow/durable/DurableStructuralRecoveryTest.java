package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.team4u.framework.flow.durable.DurableTestOps.SimulatedCrash;
import static com.team4u.framework.flow.durable.DurableTestOps.acceptedValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.ParallelResults;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

/**
 * 组11：phase=0 结构帧恢复 — start() 先提交根帧 phase=0 初始快照；sequence 归约
 * 压入下一子节点后立即 commit。崩溃发生在 selector/首分支执行期间时，栈顶即
 * phase=0 结构帧，恢复必须放行并重放（fix：不再误拒 FRAME_MISMATCH）。
 */
public class DurableStructuralRecoveryTest {

    private static Operation<String, String> crashingOp(final String tag,
                                                        final AtomicInteger calls) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (calls.incrementAndGet() == 1) {
                    throw new SimulatedCrash("crash in " + tag);
                }
                return Outcome.accepted(input + ">" + tag);
            }
        };
    }

    private static Operation<String, String> fixedKeySelector(final String key) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + ">" + key);
            }
        };
    }

    private static Operation<String, String> fixedOp(final String tag) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + ">" + tag);
            }
        };
    }

    private static JoinStrategy<String> concatJoin() {
        return new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(
                    com.team4u.framework.flow.model.ParallelResults results) {
                StringBuilder joined = new StringBuilder();
                for (Branch<?, ?> branch : results.branches()) {
                    joined.append(branch.name()).append(";");
                }
                return Outcome.accepted(joined.toString());
            }
        };
    }

    private static void assertStartCrashed(DurableExecutable<String, ?> executable,
                                           String executionId) {
        try {
            executable.start(executionId, "in");
            fail("首段执行必须崩溃");
        } catch (SimulatedCrash expected) {
            // 崩溃：phase=0 结构帧快照已落库
        }
    }

    // ------------------------------------------------------------------
    // 根为结构节点：初始快照（revision=1）中根帧 phase=0
    // ------------------------------------------------------------------

    @Test
    public void routeRootSelectorCrashRecoversFromPhaseZero() {
        AtomicInteger selectorCalls = new AtomicInteger();
        Operation<String, String> selector = crashingOp("selector", selectorCalls);
        Flow<String, String> flow = Flow.<String, String>route(selector)
                .caseOf("go", Flow.<String>identity())
                .withoutOtherwise();
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                Durable.builder(store).build().compile(flow, "struct", 1);
        assertStartCrashed(executable, "e");
        DurableResult<String> result = executable.recover("e");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        // selector 重放返回 "in>selector"，不匹配 "go" → NO_ROUTE skipped 终态
        assertEquals(Outcome.Kind.SKIPPED,
                ((DurableResult.Completed<String>) result).outcome().kind());
        assertEquals(2, selectorCalls.get());
    }

    @Test
    public void fallbackRootFirstBranchCrashRecoversFromPhaseZero() {
        AtomicInteger firstCalls = new AtomicInteger();
        Flow<String, String> flow = Flow.firstApplicable(
                Flow.<String, String>step(crashingOp("first", firstCalls)),
                Flow.<String, String>skipped(Reason.of("NA", "na")));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                Durable.builder(store).build().compile(flow, "struct", 1);
        assertStartCrashed(executable, "e");
        DurableResult<String> result = executable.recover("e");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        assertEquals("in>first", acceptedValue(result));
        assertEquals(2, firstCalls.get());
    }

    @Test
    public void parallelRootFirstBranchCrashRecoversFromPhaseZero() {
        AtomicInteger branchCalls = new AtomicInteger();
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.<String, String>of("b",
                        Flow.<String, String>step(crashingOp("branch", branchCalls))))
                .join(concatJoin());
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                Durable.builder(store).build().compile(flow, "struct", 1);
        assertStartCrashed(executable, "e");
        DurableResult<String> result = executable.recover("e");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        assertEquals("b;", acceptedValue(result));
        assertEquals(2, branchCalls.get());
    }

    // ------------------------------------------------------------------
    // 嵌套形态：sequence 完成后压入结构帧即崩溃（检查点栈顶 phase=0）
    // ------------------------------------------------------------------

    @Test
    public void nestedRouteEnteredAfterSequenceCrashRecovers() {
        final AtomicInteger selectorCalls = new AtomicInteger();
        Operation<String, String> selector = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (selectorCalls.incrementAndGet() == 1) {
                    throw new SimulatedCrash("crash in nested selector");
                }
                return Outcome.accepted("go");
            }
        };
        // sequence 首子（不崩溃）完成后归约压入 route 并 commit，
        // 崩溃发生在 selector 首调：快照栈顶为 phase=0 的 Route 帧
        Flow<String, String> flow = Flow.<String, String>step(fixedOp("head")).then(
                Flow.<String, String>route(selector)
                        .caseOf("go", Flow.<String>identity())
                        .otherwise(Flow.<String>identity()));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                Durable.builder(store).build().compile(flow, "struct", 1);
        try {
            executable.start("e", "in");
            fail("嵌套 selector 首调必须崩溃");
        } catch (SimulatedCrash expected) {
            // 崩溃：栈顶为 phase=0 的 Route 帧（sequence 归约压入后已 commit）
        }
        DurableResult<String> result = executable.recover("e");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        assertEquals("in>head", acceptedValue(result));
        assertEquals(2, selectorCalls.get());
    }

    @Test
    public void nestedFallbackEnteredAfterSequenceCrashRecovers() {
        final AtomicInteger branchCalls = new AtomicInteger();
        Operation<String, String> body = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (branchCalls.incrementAndGet() == 1) {
                    throw new SimulatedCrash("crash in fallback branch");
                }
                return Outcome.accepted(input + ">fb");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(fixedOp("head")).then(
                Flow.firstApplicable(
                        Flow.<String, String>step(body),
                        Flow.<String, String>skipped(Reason.of("NA", "na"))));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                Durable.builder(store).build().compile(flow, "struct", 1);
        try {
            executable.start("e", "in");
            fail("fallback 首分支首调必须崩溃");
        } catch (SimulatedCrash expected) {
            // 崩溃：栈顶为 phase=0 的 Fallback 帧
        }
        DurableResult<String> result = executable.recover("e");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        assertEquals("in>head>fb", acceptedValue(result));
        assertEquals(2, branchCalls.get());
    }

    @Test
    public void nestedParallelEnteredAfterSequenceCrashRecovers() {
        final AtomicInteger branchCalls = new AtomicInteger();
        Flow<String, String> flow = Flow.<String, String>step(fixedOp("head")).then(
                Flow.<String>parallel(
                        Branch.<String, String>of("p",
                                Flow.<String, String>step(
                                        crashingOp("pbranch", branchCalls))))
                        .join(concatJoin()));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                Durable.builder(store).build().compile(flow, "struct", 1);
        try {
            executable.start("e", "in");
            fail("parallel 首分支首调必须崩溃");
        } catch (SimulatedCrash expected) {
            // 崩溃：栈顶为 phase=0 的 Parallel 帧
        }
        DurableResult<String> result = executable.recover("e");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        assertEquals("p;", acceptedValue(result));
        assertEquals(2, branchCalls.get());
    }
}
