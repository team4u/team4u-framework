package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.team4u.framework.flow.durable.DurableTestOps.SimulatedCrash;
import static com.team4u.framework.flow.durable.DurableTestOps.acceptedValue;
import static com.team4u.framework.flow.durable.DurableTestOps.failed;
import static com.team4u.framework.flow.durable.DurableTestOps.rejected;
import static com.team4u.framework.flow.durable.DurableTestOps.skipped;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.ParallelResults;
import com.team4u.framework.flow.model.Reason;

/** 组6：parallel — 声明顺序 join、四种业务 Outcome、崩溃恢复只跑剩余分支、join 异常、全失败/全跳过。 */
public class DurableParallelTest {

    /** 返回固定 Outcome 的 Operation，可跨 token 重复使用。 */
    private static Operation<String, String> fixed(final String tag, final Outcome<String> outcome) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return outcome == null
                        ? Outcome.accepted(input + ">" + tag)
                        : outcome;
            }
        };
    }

    /** 首次调用抛 Error（模拟崩溃），之后成功的 Operation。 */
    private static Operation<String, String> crashingFirst(final String tag,
                                                           final AtomicInteger calls) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (calls.incrementAndGet() == 1) {
                    throw new SimulatedCrash("crash in branch " + tag);
                }
                return Outcome.accepted(input + ">" + tag);
            }
        };
    }

    private static DurableExecutable<String, String> compile(Flow<String, String> flow,
                                                             DurableStore store) {
        return DurableRuntime.builder(store).build().compile(flow, "par", 1);
    }

    private static Outcome<String> outcome(DurableResult<String> result) {
        return ((DurableResult.Completed<String>) result).outcome();
    }

    @Test
    public void joinReceivesBranchesInDeclarationOrder() {
        // 计数 join：记录 ParallelResults.branches() 的名称顺序
        final List<String> seenOrder = new ArrayList<String>();
        JoinStrategy<String> recorder = new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                for (Branch<?, ?> branch : results.branches()) {
                    seenOrder.add(branch.name());
                }
                return Outcome.accepted("joined");
            }
        };
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger cCalls = new AtomicInteger();
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("alpha", fixed("A", null)),
                Branch.of("beta", fixed("B", null)),
                Branch.of("gamma", fixed("C", null))
        ).join(recorder);
        DurableResult<String> result = compile(flow, new InMemoryDurableStore())
                .start("e", "in");
        assertEquals("joined", acceptedValue(result));
        assertEquals("join 必须收到声明顺序的分支",
                java.util.Arrays.asList("alpha", "beta", "gamma"), seenOrder);
    }

    @Test
    public void joinReturnsRejectedOutcome() {
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("l", fixed("l", null)),
                Branch.of("r", fixed("r", null))
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                return Outcome.rejected(Reason.of("JOIN_REJECT", "no"));
            }
        });
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "in");
        assertEquals(Outcome.Kind.REJECTED, outcome(result).kind());
        assertEquals("JOIN_REJECT",
                ((Outcome.Rejected<String>) outcome(result)).reason().code());
    }

    @Test
    public void joinReturnsSkippedOutcome() {
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("l", fixed("l", null)),
                Branch.of("r", fixed("r", null))
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                return Outcome.skipped(Reason.of("JOIN_SKIP", "na"));
            }
        });
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "in");
        assertEquals(Outcome.Kind.SKIPPED, outcome(result).kind());
    }

    @Test
    public void joinReturnsFailedOutcome() {
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("l", fixed("l", null)),
                Branch.of("r", fixed("r", null))
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                return Outcome.failed(Failure.of("JOIN_FAIL", "no"));
            }
        });
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "in");
        assertEquals(Outcome.Kind.FAILED, outcome(result).kind());
        assertEquals("JOIN_FAIL",
                ((Outcome.Failed<String>) outcome(result)).failure().code());
    }

    @Test
    public void joinExceptionBecomesStableFailedOutcome() {
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("l", fixed("l", null)),
                Branch.of("r", fixed("r", null))
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                throw new IllegalStateException("join exploded");
            }
        });
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "in");
        Outcome.Failed<String> failedOutcome = (Outcome.Failed<String>) outcome(result);
        assertEquals("JOIN_EXCEPTION", failedOutcome.failure().code());
        assertTrue(failedOutcome.failure().message().contains("join exploded"));
    }

    @Test
    public void crashAfterPartialBranchesRunsOnlyRemainingOnRecover() {
        // alpha 首次崩溃：由于分支完成即 checkpoint，recover 后 alpha 重放、
        // 已完成的 beta 不再执行（计数 Operation 验证）
        final AtomicInteger alphaCalls = new AtomicInteger();
        final AtomicInteger betaCalls = new AtomicInteger();
        final AtomicInteger gammaCalls = new AtomicInteger();
        Operation<String, String> alpha = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (alphaCalls.incrementAndGet() == 1) {
                    throw new SimulatedCrash("crash in alpha");
                }
                return Outcome.accepted(input + ">alpha");
            }
        };
        Operation<String, String> beta = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                betaCalls.incrementAndGet();
                return Outcome.accepted(input + ">beta");
            }
        };
        Operation<String, String> gamma = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                gammaCalls.incrementAndGet();
                return Outcome.accepted(input + ">gamma");
            }
        };
        // 声明顺序：beta（先完成）→ alpha（崩溃）→ gamma（未开始）
        // 机器按顺序驱动分支：首个 null 槽位即执行。
        // beta 在 alpha 之前声明，因此 crash 时 beta 已 checkpoint、gamma 未跑。
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("beta", beta),
                Branch.of("alpha", alpha),
                Branch.of("gamma", gamma)
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                StringBuilder joined = new StringBuilder();
                for (Branch<?, ?> branch : results.branches()) {
                    joined.append(branch.name()).append(";");
                }
                return Outcome.accepted(joined.toString());
            }
        });
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        try {
            executable.start("e", "in");
            fail("alpha 首次执行必须崩溃");
        } catch (SimulatedCrash expected) {
            // crash：beta 已提交
        }
        DurableResult<String> result = executable.recover("e");
        assertTrue(result.getClass().getSimpleName(),
                result instanceof DurableResult.Completed);
        assertEquals("beta;alpha;gamma;", acceptedValue(result));
        assertEquals("beta 已 checkpoint 不重跑", 1, betaCalls.get());
        assertEquals("alpha 崩溃后重跑", 2, alphaCalls.get());
        assertEquals("gamma 只在 recover 后执行", 1, gammaCalls.get());
    }

    @Test
    public void allFailedBranchesPropagateToJoin() {
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("l", fixed("l", failed("L_BAD"))),
                Branch.of("r", fixed("r", failed("R_BAD")))
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                // 全失败：join 收到两个 Failed，选择透传第一个
                Outcome<?> first = results.outcome(results.branches().get(0));
                return castFailed(first);
            }
        });
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "in");
        assertEquals(Outcome.Kind.FAILED, outcome(result).kind());
        assertEquals("L_BAD", ((Outcome.Failed<String>) outcome(result)).failure().code());
    }

    @Test
    public void allSkippedBranchesPropagateToJoin() {
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("l", fixed("l", skipped("L_NA"))),
                Branch.of("r", fixed("r", skipped("R_NA")))
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                return castSkipped(results.outcome(results.branches().get(1)));
            }
        });
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "in");
        assertEquals(Outcome.Kind.SKIPPED, outcome(result).kind());
        assertEquals("R_NA", ((Outcome.Skipped<String>) outcome(result)).reason().code());
    }

    @Test
    public void rejectedBranchOutcomeIsVisibleToJoin() {
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("l", fixed("l", rejected("L_NO"))),
                Branch.of("r", fixed("r", null))
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                return castRejected(results.outcome(results.branches().get(0)));
            }
        });
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "in");
        assertEquals(Outcome.Kind.REJECTED, outcome(result).kind());
        assertEquals("L_NO", ((Outcome.Rejected<String>) outcome(result)).reason().code());
    }

    @Test
    public void parallelBranchesRunSequentiallyInDeclarationOrder() {
        // 锁定串行驱动合同：后序分支开始时前序分支必须已完成（计数器验证），
        // Durable 不做并发执行；需要并发请使用 Core Local 执行器（见 DurableRuntime javadoc）。
        final List<String> trace = new ArrayList<String>();
        final AtomicInteger running = new AtomicInteger();
        Operation<String, String> traced = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (running.incrementAndGet() > 1) {
                    trace.add("OVERLAP");
                }
                trace.add("start:" + input);
                // 留出可重叠窗口：若实现并发，这里几乎必然重叠
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                trace.add("end:" + input);
                running.decrementAndGet();
                return Outcome.accepted(input);
            }
        };
        Operation<String, String> first = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                trace.add("start:first");
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                trace.add("end:first");
                return Outcome.accepted("first-done");
            }
        };
        Operation<String, String> second = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                // 串行合同：second 开始时 first 必须已 end
                if (!trace.contains("end:first")) {
                    trace.add("SECOND_BEFORE_FIRST_END");
                }
                trace.add("start:second");
                return Outcome.accepted("second-done");
            }
        };
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("first", first),
                Branch.of("second", second)
        ).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                return Outcome.accepted("joined");
            }
        });
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "in");
        assertEquals("joined", acceptedValue(result));
        assertFalse("分支不得重叠执行", trace.contains("OVERLAP"));
        assertFalse("后序分支开始前前序分支必须完成",
                trace.contains("SECOND_BEFORE_FIRST_END"));
        assertTrue("前序分支必须先结束",
                trace.indexOf("end:first") < trace.indexOf("start:second"));
    }

    @SuppressWarnings("unchecked")
    private static Outcome<String> castFailed(Outcome<?> outcome) {
        return (Outcome<String>) outcome;
    }

    @SuppressWarnings("unchecked")
    private static Outcome<String> castSkipped(Outcome<?> outcome) {
        return (Outcome<String>) outcome;
    }

    @SuppressWarnings("unchecked")
    private static Outcome<String> castRejected(Outcome<?> outcome) {
        return (Outcome<String>) outcome;
    }
}
