package com.team4u.framework.flow;

import org.junit.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.spi.OperationResolver;
/**
 * 运行期失败诊断码验证：Operation 异常、并行分支异常、Join 策略异常、Policy 回调异常、
 * 无匹配路由、执行器拒绝等典型失败路径必须落定为规范诊断码（而非异常外抛或静默吞并）。
 */
public class RuntimeFailureCodesTest {

    private static String failureCode(FlowResult<?> result) {
        FlowResult.Completed<?> completed = (FlowResult.Completed<?>) result;
        Outcome<?> outcome = completed.outcome();
        assertTrue("expected FAILED outcome but was " + outcome.kind(),
                outcome instanceof Outcome.Failed<?>);
        return ((Outcome.Failed<?>) outcome).failure().code();
    }

    @Test
    public void operationRuntimeExceptionProducesOperationException() {
        Operation<String, String> boom = (context, input) -> {
            throw new IllegalStateException("op-boom");
        };
        assertEquals("OPERATION_EXCEPTION",
                failureCode(Local.compile(Flow.step(boom)).run("in")));
    }

    @Test
    public void parallelBranchRuntimeExceptionPropagatesBranchFailure() {
        Branch<String, String> bad = Branch.of("bad", (context, input) -> {
            throw new IllegalStateException("branch-boom");
        });
        Branch<String, String> good = Branch.of("good",
                (Operation<String, String>) (context, input) -> Outcome.accepted("ok"));
        // 分支内的 Operation 异常在分支机器内落定为 OPERATION_EXCEPTION，
        // 再经 allAccepted 透传为并行整体结果（而非 PARALLEL_EXCEPTION）。
        assertEquals("OPERATION_EXCEPTION", failureCode(Local.compile(
                Flow.parallel(bad, good).join(results -> results.allAccepted()
                        .map(values -> "joined"))).run("in")));
    }

    @Test
    public void joinStrategyRuntimeExceptionProducesJoinException() {
        Branch<String, String> only = Branch.of("only", Flow.identity());
        assertEquals("JOIN_EXCEPTION", failureCode(Local.compile(
                Flow.parallel(only).join(results -> {
                    throw new IllegalStateException("join-boom");
                })).run("in")));
    }

    @Test
    public void policyCallbackRuntimeExceptionProducesPolicyException() {
        Policy<String> broken = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) {
                throw new IllegalStateException("policy-boom");
            }
        };
        assertEquals("POLICY_EXCEPTION", failureCode(Local.compile(
                Flow.<String>identity().policy(broken, value -> value)).run("in")));
    }

    @Test
    public void routeWithoutMatchAndOtherwiseProducesNoRouteSkip() {
        FlowResult<?> result = Local.compile(
                Flow.route((Operation<String, Integer>) (context, input) ->
                                Outcome.accepted(input.length()))
                        .caseOf(99, Flow.accepted("ninety-nine"))
                        .withoutOtherwise()).run("abc");
        // 无匹配且无兜底分支产生 SKIPPED（而非 FAILED），原因码为 NO_ROUTE
        Outcome<?> outcome = ((FlowResult.Completed<?>) result).outcome();
        assertTrue(outcome instanceof Outcome.Skipped<?>);
        assertEquals("NO_ROUTE", ((Outcome.Skipped<?>) outcome).reason().code());
    }

    @Test
    public void rejectingTimedExecutorProducesExecutorRejected() {
        java.util.concurrent.ExecutorService rejecting = new AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) {
                throw new java.util.concurrent.RejectedExecutionException("rejected");
            }
        };
        Flow<String, String> timed = Flow.step(
                (Operation<String, String>) (context, input) -> Outcome.accepted(input))
                .timeout(Duration.ofSeconds(2));
        assertEquals("EXECUTOR_REJECTED", failureCode(
                Local.from(timed).executor(rejecting).compile().run("in", Cancellation.create())));
    }

    @Test
    public void nestedFailurePropagatesThroughScopesUntouched() {
        final AtomicInteger survivors = new AtomicInteger();
        Operation<String, String> never = (context, input) -> {
            survivors.incrementAndGet();
            return Outcome.accepted(input);
        };
        Operation<String, String> boom = (context, input) -> {
            throw new IllegalStateException("deep-boom");
        };
        Flow<String, String> flow = Flow.scope("outer",
                Flow.scope("inner", Flow.step(boom).then(never)));
        assertEquals("OPERATION_EXCEPTION", failureCode(Local.compile(flow).run("in")));
        assertEquals("失败后同作用域后续节点不得执行", 0, survivors.get());
    }
}
