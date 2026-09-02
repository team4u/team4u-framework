package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.model.Resumed;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FlowDslTest {

    static class OrderContext {
        String orderId;
        int itemsCount;
        boolean paid;
        List<String> logs = new ArrayList<String>();

        OrderContext(String orderId, int itemsCount, boolean paid) {
            this.orderId = orderId;
            this.itemsCount = itemsCount;
            this.paid = paid;
        }
    }

    enum OrderState {
        PAID,
        UNPAID
    }

    @Test
    public void testEndToEndFlowExecution() {
        String dsl = "schema 1\n" +
                "flow order.process version 1 {\n" +
                "    step order.validate\n" +
                "    route order.status {\n" +
                "        case PAID {\n" +
                "            step order.confirm\n" +
                "        }\n" +
                "        case UNPAID {\n" +
                "            step order.cancel\n" +
                "        }\n" +
                "    }\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", (OperationContext ctx, OrderContext in) -> {
                    in.logs.add("validated");
                    return Outcome.accepted(in);
                }, OrderContext.class, OrderContext.class)
                .operation("order.status", (OperationContext ctx, OrderContext in) -> {
                    return Outcome.accepted(in.paid ? OrderState.PAID : OrderState.UNPAID);
                }, OrderContext.class, OrderState.class)
                .operation("order.confirm", (OperationContext ctx, OrderContext in) -> {
                    in.logs.add("confirmed");
                    return Outcome.accepted(in);
                }, OrderContext.class, OrderContext.class)
                .operation("order.cancel", (OperationContext ctx, OrderContext in) -> {
                    in.logs.add("cancelled");
                    return Outcome.accepted(in);
                }, OrderContext.class, OrderContext.class)
                .build();

        BoundFlow bound = FlowDsl.bind(dsl, "order.flow", registry);
        LocalExecutable<OrderContext, OrderContext> exec = bound.compileLocal(OrderContext.class, OrderContext.class);

        // 验证 PAID 分支
        OrderContext ctxPaid = new OrderContext("O1", 2, true);
        FlowResult<OrderContext> resultPaid = exec.run(ctxPaid);
        OrderContext outPaid = resultPaid.requireAccepted();
        Assert.assertEquals(2, outPaid.logs.size());
        Assert.assertEquals("validated", outPaid.logs.get(0));
        Assert.assertEquals("confirmed", outPaid.logs.get(1));

        // 验证 UNPAID 分支
        OrderContext ctxUnpaid = new OrderContext("O2", 1, false);
        FlowResult<OrderContext> resultUnpaid = exec.run(ctxUnpaid);
        OrderContext outUnpaid = resultUnpaid.requireAccepted();
        Assert.assertEquals(2, outUnpaid.logs.size());
        Assert.assertEquals("validated", outUnpaid.logs.get(0));
        Assert.assertEquals("cancelled", outUnpaid.logs.get(1));
    }

    @Test
    public void testProjectAndMergeExecution() {
        String dsl = "flow inventory.flow {\n" +
                "    step inventory.reserve {\n" +
                "        project order.items\n" +
                "        merge order.withReservation\n" +
                "        timeout 1s\n" +
                "    }\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .projector("order.items", OrderContext.class, Integer.class, in -> in.itemsCount)
                .merger("order.withReservation", OrderContext.class, String.class, OrderContext.class, (in, res) -> {
                    in.logs.add("reserved:" + res);
                    return in;
                })
                .operation("inventory.reserve", (OperationContext ctx, Integer count) -> {
                    return Outcome.accepted("RES_" + count);
                }, Integer.class, String.class)
                .build();

        BoundFlow bound = FlowDsl.bind(dsl, registry);
        LocalExecutable<OrderContext, OrderContext> exec = bound.compileLocal(OrderContext.class, OrderContext.class);
        OrderContext ctx = new OrderContext("O3", 5, true);
        FlowResult<OrderContext> result = exec.run(ctx);
        OrderContext out = result.requireAccepted();
        Assert.assertEquals(1, out.logs.size());
        Assert.assertEquals("reserved:RES_5", out.logs.get(0));
    }

    @Test
    public void testParallelAndJoinExecution() {
        String dsl = "flow parallel.demo {\n" +
                "    parallel {\n" +
                "        branch risk {\n" +
                "            step check.risk\n" +
                "        }\n" +
                "        branch inventory {\n" +
                "            step check.inventory\n" +
                "        }\n" +
                "        join check.summary\n" +
                "    }\n" +
                "}";

        JoinStrategy<String> summaryJoin = results -> {
            return Outcome.accepted("ALL_PASSED_COUNT=" + results.branches().size());
        };

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("check.risk", (OperationContext ctx, String in) -> Outcome.accepted("RISK_OK"), String.class, String.class)
                .operation("check.inventory", (OperationContext ctx, String in) -> Outcome.accepted("INV_OK"), String.class, String.class)
                .join("check.summary", summaryJoin, String.class)
                .build();

        BoundFlow bound = FlowDsl.bind(dsl, registry);
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        FlowResult<String> result = exec.run("order123");
        Assert.assertEquals("ALL_PASSED_COUNT=2", result.requireAccepted());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAwaitExecution() {
        String dsl = "flow callback.demo {\n" +
                "    await payment.callback\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .resumePoint("payment.callback", String.class)
                .build();

        BoundFlow bound = FlowDsl.bind(dsl, registry);
        LocalExecutable<String, Resumed<String, String>> exec = (LocalExecutable) bound.compileLocal(String.class, Resumed.class);
        FlowResult<Resumed<String, String>> result = exec.run("init_state");
        Assert.assertTrue(result instanceof FlowResult.Suspended);
        Assert.assertTrue(((FlowResult.Suspended<Resumed<String, String>>) result).awaiting(ResumePoint.named("payment.callback")));
    }

    public static class EchoOperation implements Operation<String, String> {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted("ECHO:" + input);
        }
    }

    @Test
    public void testBindWithClassContractUsesRegistryFallbackResolver() {
        String dsl = "flow echo.demo {\n" +
                "    step echo.op\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("echo.op", EchoOperation.class)
                .fallbackResolver((contract, qualifier) -> new EchoOperation())
                .build();

        // FlowDsl.bind(dsl, registry) delegates to registry.fallbackResolver()
        BoundFlow bound = FlowDsl.bind(dsl, registry);
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        FlowResult<String> result = exec.run("hello");
        Assert.assertEquals("ECHO:hello", result.requireAccepted());
    }

    @Test
    public void testBindWithClassContractUsesCustomResolver() {
        String dsl = "flow echo.demo {\n" +
                "    step echo.op\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("echo.op", EchoOperation.class)
                .build();

        BoundFlow bound = FlowDsl.bind(dsl, registry, (contract, qualifier) -> new EchoOperation());
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        FlowResult<String> result = exec.run("world");
        Assert.assertEquals("ECHO:world", result.requireAccepted());
    }

    @Test
    public void testMultiFlowDeclarationAndDirectCall() {
        String dsl = "schema 1\n" +
                "\n" +
                "flow subflow.risk {\n" +
                "    step risk.op\n" +
                "}\n" +
                "\n" +
                "flow subflow.pay {\n" +
                "    step pay.op\n" +
                "}\n" +
                "\n" +
                "flow main.checkout {\n" +
                "    call subflow.risk\n" +
                "    call subflow.pay\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("risk.op", (OperationContext ctx, OrderContext in) -> {
                    in.logs.add("risk_checked");
                    return Outcome.accepted(in);
                }, OrderContext.class, OrderContext.class)
                .operation("pay.op", (OperationContext ctx, OrderContext in) -> {
                    in.logs.add("pay_charged");
                    return Outcome.accepted(in);
                }, OrderContext.class, OrderContext.class)
                .build();

        BoundFlow bound = FlowDsl.bindTarget(dsl, "main.checkout", registry);
        LocalExecutable<OrderContext, OrderContext> exec = bound.compileLocal(OrderContext.class, OrderContext.class);

        OrderContext ctx = new OrderContext("O100", 3, true);
        FlowResult<OrderContext> result = exec.run(ctx);
        OrderContext out = result.requireAccepted();

        Assert.assertEquals(2, out.logs.size());
        Assert.assertEquals("risk_checked", out.logs.get(0));
        Assert.assertEquals("pay_charged", out.logs.get(1));
    }

    @Test
    public void testMultiFlowWithProjectAndMergeCall() {
        String dsl = "flow calc.tax {\n" +
                "    step compute.tax\n" +
                "}\n" +
                "\n" +
                "flow main.order {\n" +
                "    call calc.tax {\n" +
                "        project order.itemsCount\n" +
                "        merge order.withTax\n" +
                "    }\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("compute.tax", (OperationContext ctx, Integer count) -> {
                    return Outcome.accepted("TAX_" + (count * 10));
                }, Integer.class, String.class)
                .projector("order.itemsCount", OrderContext.class, Integer.class, (OrderContext ctx) -> ctx.itemsCount)
                .merger("order.withTax", OrderContext.class, String.class, OrderContext.class, (OrderContext state, String tax) -> {
                    state.logs.add(tax);
                    return state;
                })
                .build();

        // 多 flow 未显式指定 target 抛出 AMBIGUOUS_TARGET_FLOW
        try {
            FlowDsl.bind(dsl, registry);
            Assert.fail("Expected FlowDiagnosticException");
        } catch (com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException ex) {
            Assert.assertEquals(com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes.AMBIGUOUS_TARGET_FLOW, ex.diagnostic().code());
        }

        BoundFlow bound = FlowDsl.bindTarget(dsl, "main.order", registry);
        LocalExecutable<OrderContext, OrderContext> exec = bound.compileLocal(OrderContext.class, OrderContext.class);

        OrderContext ctx = new OrderContext("O200", 5, true);
        FlowResult<OrderContext> result = exec.run(ctx);
        OrderContext out = result.requireAccepted();

        Assert.assertEquals(1, out.logs.size());
        Assert.assertEquals("TAX_50", out.logs.get(0));
    }

    @Test
    public void testMultiFlowBindTarget() {
        String dsl = "flow subflow.a {\n" +
                "    step op.a\n" +
                "}\n" +
                "\n" +
                "flow subflow.b {\n" +
                "    step op.b\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("op.a", (OperationContext ctx, String in) -> Outcome.accepted("A:" + in), String.class, String.class)
                .operation("op.b", (OperationContext ctx, String in) -> Outcome.accepted("B:" + in), String.class, String.class)
                .build();

        BoundFlow boundA = FlowDsl.bindTarget(dsl, "subflow.a", registry);
        LocalExecutable<String, String> execA = boundA.compileLocal(String.class, String.class);
        Assert.assertEquals("A:hello", execA.run("hello").requireAccepted());

        BoundFlow boundB = FlowDsl.bindTarget(dsl, "subflow.b", registry);
        LocalExecutable<String, String> execB = boundB.compileLocal(String.class, String.class);
        Assert.assertEquals("B:hello", execB.run("hello").requireAccepted());
    }

    @Test
    public void testCallUnknownFlowThrowsDiagnostic() {
        String dsl = "flow main {\n" +
                "    call non.existent.flow\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.empty();
        try {
            FlowDsl.bind(dsl, registry);
            Assert.fail("Expected FlowDiagnosticException");
        } catch (com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException ex) {
            Assert.assertTrue(ex.getMessage().contains("UNKNOWN_FLOW"));
        }
    }

    @Test
    public void testCallWithOptionalModifier() {
        String dsl = "flow sub.failing {\n" +
                "    step op.fail\n" +
                "}\n" +
                "\n" +
                "flow main {\n" +
                "    call sub.failing {\n" +
                "        optional\n" +
                "    }\n" +
                "    step op.succ\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("op.fail", (OperationContext ctx, OrderContext in) -> {
                    return Outcome.skipped(Reason.of("NOT_ELIGIBLE", "Skipped branch"));
                }, OrderContext.class, OrderContext.class)
                .operation("op.succ", (OperationContext ctx, OrderContext in) -> {
                    in.logs.add("recovered_and_continued");
                    return Outcome.accepted(in);
                }, OrderContext.class, OrderContext.class)
                .build();

        BoundFlow bound = FlowDsl.bindTarget(dsl, "main", registry);
        LocalExecutable<OrderContext, OrderContext> exec = bound.compileLocal(OrderContext.class, OrderContext.class);

        OrderContext ctx = new OrderContext("O300", 1, true);
        FlowResult<OrderContext> result = exec.run(ctx);
        OrderContext out = result.requireAccepted();

        Assert.assertEquals(1, out.logs.size());
        Assert.assertEquals("recovered_and_continued", out.logs.get(0));
    }

    @Test
    public void testCyclicFlowCallSelfLoopDetectedInDsl() {
        String dsl = "flow loop.self {\n" +
                "    call loop.self\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.empty();
        try {
            FlowDsl.bind(dsl, registry);
            Assert.fail("Expected FlowDiagnosticException for cyclic flow call");
        } catch (com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException ex) {
            Assert.assertTrue(ex.diagnostics().stream().anyMatch(d ->
                    com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes.CYCLIC_FLOW_CALL.equals(d.code())));
            Assert.assertTrue(ex.getMessage().contains("CYCLIC_FLOW_CALL"));
        }
    }

    @Test
    public void testMutualCyclicFlowCallDetectedInDsl() {
        String dsl = "flow flow.a {\n" +
                "    call flow.b\n" +
                "}\n" +
                "\n" +
                "flow flow.b {\n" +
                "    call flow.a\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.empty();
        try {
            FlowDsl.bindTarget(dsl, "flow.a", registry);
            Assert.fail("Expected FlowDiagnosticException for cyclic flow call");
        } catch (com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException ex) {
            Assert.assertTrue(ex.diagnostics().stream().anyMatch(d ->
                    com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes.CYCLIC_FLOW_CALL.equals(d.code())));
        }
    }
}
