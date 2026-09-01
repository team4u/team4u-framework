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
        LocalExecutable<OrderContext, OrderContext> exec = bound.compileLocal();

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
        LocalExecutable<OrderContext, OrderContext> exec = bound.compileLocal();
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
        LocalExecutable<String, String> exec = bound.compileLocal();
        FlowResult<String> result = exec.run("order123");
        Assert.assertEquals("ALL_PASSED_COUNT=2", result.requireAccepted());
    }

    @Test
    public void testAwaitExecution() {
        String dsl = "flow callback.demo {\n" +
                "    await payment.callback\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .resumePoint("payment.callback", String.class)
                .build();

        BoundFlow bound = FlowDsl.bind(dsl, registry);
        LocalExecutable<String, Resumed<String, String>> exec = bound.compileLocal();
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
        LocalExecutable<String, String> exec = bound.compileLocal();
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
        LocalExecutable<String, String> exec = bound.compileLocal();
        FlowResult<String> result = exec.run("world");
        Assert.assertEquals("ECHO:world", result.requireAccepted());
    }
}
