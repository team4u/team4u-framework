package com.team4u.framework.flow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowDiagnosticCodes;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 流程核心便捷 API 单元测试（UseBuilder、when/otherwise、tap、adapt、parallelFill 与 Joins）。
 */
public class FlowConvenienceApiTest {

    // ------------------------------------------------------------------
    // UseBuilder 测试
    // ------------------------------------------------------------------

    @Test
    public void useWithProjectAndMerge() {
        Operation<Integer, String> intToStr = (ctx, i) -> Outcome.accepted("val:" + i);

        Flow<Integer, String> flow = Flow.<Integer>identity()
                .use(intToStr)
                .project(i -> i * 2)
                .merge((orig, res) -> orig + " -> " + res);

        String result = Local.from(flow).compile().run(10).requireAccepted();
        assertEquals("10 -> val:20", result);
    }

    @Test
    public void useWithDiscardResultPreservesInputOnAccepted() {
        AtomicInteger auditLogged = new AtomicInteger(0);
        Operation<Integer, String> auditOp = (ctx, i) -> {
            auditLogged.addAndGet(i);
            return Outcome.accepted("AUDIT_OK");
        };

        Flow<Integer, Integer> flow = Flow.<Integer>identity()
                .use(auditOp)
                .project(i -> i * 10)
                .discardResult();

        Integer result = Local.from(flow).compile().run(5).requireAccepted();
        assertEquals(Integer.valueOf(5), result);
        assertEquals(50, auditLogged.get());
    }

    @Test
    public void useWithReplaceWithResult() {
        Operation<String, Integer> parseOp = (ctx, s) -> Outcome.accepted(Integer.parseInt(s));

        Flow<String, Integer> flow = Flow.<String>identity()
                .use(parseOp)
                .project(s -> s.trim())
                .replaceWithResult();

        Integer result = Local.from(flow).compile().run(" 42 ").requireAccepted();
        assertEquals(Integer.valueOf(42), result);
    }

    @Test
    public void useModifiersAreImmutable() {
        Operation<String, String> echo = (ctx, s) -> Outcome.accepted(s);

        Flow.UseMergeStage<String, String, String, String> stage = Flow.<String>identity()
                .use(echo)
                .project(s -> s);

        Flow<String, String> f1 = stage.named("step1").timeout(Duration.ofMillis(500)).discardResult();
        Flow<String, String> f2 = stage.named("step2").timeout(Duration.ofMillis(800)).discardResult();

        assertEquals("hello", Local.from(f1).compile().run("hello").requireAccepted());
        assertEquals("world", Local.from(f2).compile().run("world").requireAccepted());
    }

    // ------------------------------------------------------------------
    // when / otherwise 测试
    // ------------------------------------------------------------------

    @Test
    public void whenOtherwiseExecutesMatchedBranch() {
        Flow<Integer, String> evenFlow = Flow.step((ctx, in) -> Outcome.accepted("even:" + in));
        Flow<Integer, String> oddFlow = Flow.step((ctx, in) -> Outcome.accepted("odd:" + in));

        Flow<Integer, String> flow = Flow.<Integer>identity()
                .when(i -> i % 2 == 0, evenFlow)
                .otherwise(oddFlow);

        assertEquals("even:4", Local.from(flow).compile().run(4).requireAccepted());
        assertEquals("odd:5", Local.from(flow).compile().run(5).requireAccepted());
    }

    @Test
    public void whenChainedMatchesFirstInOrder() {
        Flow<Integer, String> smallFlow = Flow.step((ctx, in) -> Outcome.accepted("small"));
        Flow<Integer, String> midFlow = Flow.step((ctx, in) -> Outcome.accepted("mid"));
        Flow<Integer, String> largeFlow = Flow.step((ctx, in) -> Outcome.accepted("large"));

        Flow<Integer, String> flow = Flow.<Integer>identity()
                .when(i -> i < 10, smallFlow)
                .when(i -> i < 100, midFlow)
                .otherwise(largeFlow);

        assertEquals("small", Local.from(flow).compile().run(5).requireAccepted());
        assertEquals("mid", Local.from(flow).compile().run(50).requireAccepted());
        assertEquals("large", Local.from(flow).compile().run(500).requireAccepted());
    }

    @Test
    public void whenWithoutOtherwiseDefaultsToIdentity() {
        Flow<Integer, Integer> doubleFlow = Flow.step((ctx, in) -> Outcome.accepted(in * 2));

        Flow<Integer, Integer> flow = Flow.<Integer>identity()
                .when(i -> i > 10, doubleFlow)
                .otherwise();

        assertEquals(Integer.valueOf(24), Local.from(flow).compile().run(12).requireAccepted());
        assertEquals(Integer.valueOf(7), Local.from(flow).compile().run(7).requireAccepted());
    }

    // ------------------------------------------------------------------
    // tap 测试
    // ------------------------------------------------------------------

    @Test
    public void tapOperationPreservesAcceptedState() {
        AtomicBoolean tapped = new AtomicBoolean(false);
        Operation<String, String> tapOp = (ctx, in) -> {
            tapped.set(true);
            return Outcome.accepted("ignored");
        };

        Flow<String, String> flow = Flow.<String>identity().tap(tapOp);
        String result = Local.from(flow).compile().run("data").requireAccepted();
        assertEquals("data", result);
        assertTrue(tapped.get());
    }

    @Test
    public void tapOperationPropagatesNonAccepted() {
        Operation<String, String> failingOp = (ctx, in) -> Outcome.rejected(Reason.of("REJECTED", "bad data"));
        Flow<String, String> flow = Flow.<String>identity().tap(failingOp);

        FlowResult<String> res = Local.from(flow).compile().run("data");
        Outcome<String> outcome = ((FlowResult.Completed<String>) res).outcome();
        assertTrue(outcome instanceof Outcome.Rejected<?>);
    }

    @Test
    public void tapConsumerInvoked() {
        List<String> logs = new ArrayList<String>();
        Flow<String, String> flow = Flow.<String>identity().tap(s -> logs.add(s));

        String result = Local.from(flow).compile().run("message").requireAccepted();
        assertEquals("message", result);
        assertEquals(Collections.singletonList("message"), logs);
    }

    // ------------------------------------------------------------------
    // adapt 测试
    // ------------------------------------------------------------------

    @Test
    public void adaptTransformsInputAndOutput() {
        Flow<Integer, Integer> subflow = Flow.step((ctx, i) -> Outcome.accepted(i * 10));

        Flow<String, String> flow = Flow.<String>identity()
                .thenAdapt(subflow, Integer::parseInt, (s, r) -> s + "=" + r);

        String res = Local.from(flow).compile().run("5").requireAccepted();
        assertEquals("5=50", res);
    }

    @Test
    public void adaptPropagatesProjectionExceptionAsFailure() {
        Flow<Integer, Integer> subflow = Flow.step((ctx, i) -> Outcome.accepted(i * 10));

        Flow<String, String> flow = Flow.<String>identity()
                .thenAdapt(subflow, s -> { throw new IllegalArgumentException("invalid int format"); }, (s, r) -> s + "=" + r);

        FlowResult<String> res = Local.from(flow).compile().run("abc");
        Outcome<String> outcome = ((FlowResult.Completed<String>) res).outcome();
        assertTrue(outcome instanceof Outcome.Failed<?>);
        assertEquals("OPERATION_EXCEPTION", ((Outcome.Failed<?>) outcome).failure().code());
    }

    @Test
    public void adaptPropagatesChildNonAcceptedOutcome() {
        Flow<Integer, Integer> subflow = Flow.step((ctx, i) -> Outcome.rejected(Reason.of("SUB_REJECT", "too large")));

        Flow<String, String> flow = Flow.<String>identity()
                .thenAdapt(subflow, Integer::parseInt, (s, r) -> s + "=" + r);

        FlowResult<String> res = Local.from(flow).compile().run("100");
        Outcome<String> outcome = ((FlowResult.Completed<String>) res).outcome();
        assertTrue(outcome instanceof Outcome.Rejected<?>);
        assertEquals("SUB_REJECT", ((Outcome.Rejected<?>) outcome).reason().code());
    }

    // ------------------------------------------------------------------
    // parallelFill 测试
    // ------------------------------------------------------------------

    private static class OrderState {
        String orderId;
        String userVipLevel;
        Integer inventoryCount;

        OrderState(String orderId) {
            this.orderId = orderId;
        }
    }

    @Test
    public void parallelFillPopulatesStateDeterministically() {
        Operation<String, String> fetchVip = (ctx, orderId) -> Outcome.accepted("VIP_DIAMOND");
        Operation<String, Integer> fetchStock = (ctx, orderId) -> Outcome.accepted(100);

        Flow<OrderState, OrderState> flow = Flow.<OrderState>identity()
                .parallelFill()
                .fork(s -> s.orderId, fetchVip, (s, vip) -> { s.userVipLevel = vip; return s; })
                .fork(s -> s.orderId, fetchStock, (s, stock) -> { s.inventoryCount = stock; return s; })
                .timeout(Duration.ofSeconds(2))
                .end();

        OrderState initial = new OrderState("ORD-001");
        OrderState result = Local.from(flow).compile().run(initial).requireAccepted();

        assertEquals("VIP_DIAMOND", result.userVipLevel);
        assertEquals(Integer.valueOf(100), result.inventoryCount);
    }

    @Test
    public void parallelFillPropagatesFirstNonAcceptedInDeclarationOrder() {
        Operation<String, String> op1 = (ctx, id) -> Outcome.skipped(Reason.of("SKIP_1", "skip first"));
        Operation<String, String> op2 = (ctx, id) -> Outcome.rejected(Reason.of("REJECT_2", "reject second"));

        Flow<OrderState, OrderState> flow = Flow.<OrderState>identity()
                .parallelFill()
                .fork(s -> s.orderId, op1, (s, res) -> s)
                .fork(s -> s.orderId, op2, (s, res) -> s)
                .end();

        OrderState initial = new OrderState("ORD-001");
        FlowResult<OrderState> res = Local.from(flow).compile().run(initial);
        Outcome<OrderState> outcome = ((FlowResult.Completed<OrderState>) res).outcome();

        assertTrue(outcome instanceof Outcome.Skipped<?>);
        assertEquals("SKIP_1", ((Outcome.Skipped<?>) outcome).reason().code());
    }

    // ------------------------------------------------------------------
    // Joins 测试
    // ------------------------------------------------------------------

    @Test
    public void joinsAllAcceptedSuccess() {
        Branch<String, Integer> b1 = Branch.of("b1", (ctx, in) -> Outcome.accepted(10));
        Branch<String, Integer> b2 = Branch.of("b2", (ctx, in) -> Outcome.accepted(20));

        Flow<String, com.team4u.framework.flow.model.ParallelResults.Values> flow =
                Flow.parallel(b1, b2).join(Joins.allAccepted());
        com.team4u.framework.flow.model.ParallelResults.Values vals =
                Local.from(flow).compile().run("start").requireAccepted();
        assertEquals(Integer.valueOf(10), vals.get(b1));
        assertEquals(Integer.valueOf(20), vals.get(b2));
    }

    @Test
    public void joinsAllAcceptedFailsIfOneNotAccepted() {
        Branch<String, Integer> b1 = Branch.of("b1", (ctx, in) -> Outcome.accepted(10));
        Branch<String, Integer> b2 = Branch.of("b2", (ctx, in) -> Outcome.rejected(Reason.of("ERR", "failed branch")));

        Flow<String, com.team4u.framework.flow.model.ParallelResults.Values> flow =
                Flow.parallel(b1, b2).join(Joins.allAccepted());
        FlowResult<com.team4u.framework.flow.model.ParallelResults.Values> res =
                Local.from(flow).compile().run("start");
        Outcome<?> outcome = ((FlowResult.Completed<?>) res).outcome();
        assertTrue(outcome instanceof Outcome.Rejected<?>);
    }

    @Test
    public void joinsFirstAccepted() {
        Branch<String, String> b1 = Branch.of("b1", (ctx, in) -> Outcome.skipped(Reason.of("SKIP", "skip")));
        Branch<String, String> b2 = Branch.of("b2", (ctx, in) -> Outcome.accepted("first-accepted"));
        Branch<String, String> b3 = Branch.of("b3", (ctx, in) -> Outcome.accepted("second-accepted"));

        Flow<String, String> flow = Flow.parallel(b1, b2, b3).join(Joins.firstAccepted());
        String val = Local.from(flow).compile().run("start").requireAccepted();
        assertEquals("first-accepted", val);
    }

    @Test
    public void joinsCollectGathersAllOutcomes() {
        Branch<String, String> b1 = Branch.of("b1", (ctx, in) -> Outcome.accepted("ok"));
        Branch<String, String> b2 = Branch.of("b2", (ctx, in) -> Outcome.accepted("fine"));

        Flow<String, List<String>> flow = Flow.parallel(b1, b2).join(Joins.collect());
        List<String> list = Local.from(flow).compile().run("start").requireAccepted();
        assertEquals(Arrays.asList("ok", "fine"), list);
    }

    @Test
    public void joinsQuorum() {
        Branch<String, Integer> b1 = Branch.of("b1", (ctx, in) -> Outcome.accepted(10));
        Branch<String, Integer> b2 = Branch.of("b2", (ctx, in) -> Outcome.accepted(20));
        Branch<String, Integer> b3 = Branch.of("b3", (ctx, in) -> Outcome.rejected(Reason.of("NO", "no")));

        Flow<String, com.team4u.framework.flow.model.ParallelResults.Values> flow =
                Flow.parallel(b1, b2, b3).join(Joins.quorum(2));
        com.team4u.framework.flow.model.ParallelResults.Values vals =
                Local.from(flow).compile().run("start").requireAccepted();
        assertEquals(Integer.valueOf(10), vals.get(b1));
        assertEquals(Integer.valueOf(20), vals.get(b2));
    }
}
