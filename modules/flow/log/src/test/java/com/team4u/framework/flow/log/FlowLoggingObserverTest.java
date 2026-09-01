package com.team4u.framework.flow.log;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.log.support.TestLogHelper;
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FlowLoggingObserverTest {

    @Data
    @TraceContext
    public static class TestOrderContext {
        private String orderId = "ORD-8888";

        @Mask(MaskType.MOBILE)
        private String mobile = "13800138000";

        @Mask(MaskType.ID_CARD_NO)
        private String idCardNo = "110101199003072345";

        private Double amount = 199.99;
        private String status = "INITIAL";

        @TraceIgnore
        private String secretKey = "INTERNAL_KEY";
    }

    @Test
    public void testFlowLoggingObserverExecution() {
        TestLogHelper logHelper = TestLogHelper.start();
        try {
            // 1. 定义多步骤流程
            Flow<TestOrderContext, TestOrderContext> flow = Flow.<TestOrderContext, TestOrderContext>step((ctx, req) -> {
                req.setStatus("VALIDATED");
                return Outcome.accepted(req);
            }).named("Step 1: Validate")
            .then(Flow.step((Operation<TestOrderContext, TestOrderContext>) (ctx, req) -> {
                req.setStatus("PAID");
                return Outcome.accepted(req);
            }).named("Step 2: Pay"));

            // 2. 构造日志观察者
            FlowLoggingObserver observer = FlowLoggingObserver.builder()
                    .loggerNamePrefix("flow.trace")
                    .printStepLogs(true)
                    .printTreeSummary(true)
                    .build();

            LocalExecutable<TestOrderContext, TestOrderContext> executable = Local.from(flow)
                    .flowId("order-test-flow")
                    .flowVersion(1)
                    .observer(observer)
                    .compile();

            // 3. 执行流程
            TestOrderContext context = new TestOrderContext();
            FlowResult<TestOrderContext> result = FlowContextHolder.runWith(context, () -> executable.run(context));

            Assert.assertTrue(result instanceof FlowResult.Completed);
            Assert.assertEquals("PAID", result.requireAccepted().getStatus());

            // 4. 校验实时日志与脱敏
            Assert.assertTrue("应当记录单步日志", logHelper.allEvents().size() >= 3);
            boolean hasMaskedMobile = logHelper.allEvents().stream().anyMatch(e -> {
                Object ctxObj = e.get("context");
                return ctxObj != null && ctxObj.toString().contains("138*****000") && !ctxObj.toString().contains("13800138000");
            });
            Assert.assertTrue("实时单步日志中的手机号必须已被脱敏", hasMaskedMobile);

            boolean hasNoSecret = logHelper.allEvents().stream().noneMatch(e -> {
                Object ctxObj = e.get("context");
                return ctxObj != null && ctxObj.toString().contains("INTERNAL_KEY");
            });
            Assert.assertTrue("@TraceIgnore 标记的字段不应出现在日志中", hasNoSecret);

            // 5. 深度校验 rootTraceNode 结构与指标
            TraceNode root = observer.rootTraceNode();
            Assert.assertNotNull("流程结束后 rootTraceNode 应当非空", root);
            Assert.assertEquals("$", root.getPath());
            Assert.assertEquals("flow: order-test-flow", root.getLabel());
            Assert.assertEquals("ACCEPTED", root.getOutcome());
            Assert.assertTrue(root.getDurationMs() >= 0);
            Assert.assertEquals(2, root.getChildren().size());

            TraceNode step1 = root.getChildren().get(0);
            Assert.assertEquals("Step 1: Validate", step1.getLabel());
            Assert.assertEquals("ACCEPTED", step1.getOutcome());

            TraceNode step2 = root.getChildren().get(1);
            Assert.assertEquals("Step 2: Pay", step2.getLabel());
            Assert.assertEquals("ACCEPTED", step2.getOutcome());

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            logHelper.stop();
        }
    }

    @Test
    public void testParallelFlowExecution() throws Exception {
        Branch<TestOrderContext, TestOrderContext> b1 = Branch.of(
                "BranchA", Flow.step((Operation<TestOrderContext, TestOrderContext>) (ctx, req) -> Outcome.accepted(req)).named("BranchA")
        );
        Branch<TestOrderContext, TestOrderContext> b2 = Branch.of(
                "BranchB", Flow.step((Operation<TestOrderContext, TestOrderContext>) (ctx, req) -> Outcome.accepted(req)).named("BranchB")
        );
        Flow<TestOrderContext, TestOrderContext> flow = Flow.parallel(b1, b2).join(results -> results.outcome(b1));

        FlowLoggingObserver observer = new FlowLoggingObserver();
        LocalExecutable<TestOrderContext, TestOrderContext> executable = Local.from(flow)
                .flowId("parallel-flow")
                .flowVersion(1)
                .observer(observer)
                .compile();

        TestOrderContext context = new TestOrderContext();
        FlowResult<TestOrderContext> result = FlowContextHolder.runWith(context, () -> executable.run(context));

        Assert.assertTrue(result instanceof FlowResult.Completed);
        TraceNode root = observer.rootTraceNode();
        Assert.assertNotNull(root);
        Assert.assertEquals("ACCEPTED", root.getOutcome());
        Assert.assertEquals(2, root.getChildren().size());
        List<String> childLabels = root.getChildren().stream().map(TraceNode::getLabel).collect(Collectors.toList());
        Assert.assertTrue(childLabels.contains("BranchA"));
        Assert.assertTrue(childLabels.contains("BranchB"));
        Assert.assertTrue(root.getChildren().stream().allMatch(c -> "ACCEPTED".equals(c.getOutcome())));
    }

    @Test
    public void testRouteAndFallbackExecutionTree() throws Exception {
        Flow<TestOrderContext, TestOrderContext> flow = Flow.route(
                        (Operation<TestOrderContext, String>) (ctx, req) -> Outcome.accepted(req.getStatus())
                )
                .caseOf("INITIAL", Flow.step((Operation<TestOrderContext, TestOrderContext>) (ctx, req) -> {
                    req.setStatus("ROUTED");
                    return Outcome.accepted(req);
                }).named("Initial Branch"))
                .otherwise(Flow.step((Operation<TestOrderContext, TestOrderContext>) (ctx, req) -> Outcome.accepted(req)));

        FlowLoggingObserver observer = FlowLoggingObserver.builder()
                .printStepLogs(true)
                .printTreeSummary(true)
                .build();

        LocalExecutable<TestOrderContext, TestOrderContext> executable = Local.from(flow)
                .flowId("route-test-flow")
                .flowVersion(1)
                .observer(observer)
                .compile();

        TestOrderContext context = new TestOrderContext();
        FlowResult<TestOrderContext> result = FlowContextHolder.runWith(context, () -> executable.run(context));

        Assert.assertTrue(result instanceof FlowResult.Completed);
        TraceNode root = observer.rootTraceNode();
        Assert.assertNotNull(root);
        Assert.assertTrue(root.getExtra().contains("selected=case:0"));
        Assert.assertEquals(2, root.getChildren().size());
        TraceNode branchChild = root.getChildren().get(1);
        Assert.assertEquals("Initial Branch", branchChild.getLabel());
    }

    @Test
    public void testNonAcceptedOutcomesLogging() throws Exception {
        TestLogHelper logHelper = TestLogHelper.start();
        try {
            Flow<TestOrderContext, TestOrderContext> rejectedFlow = Flow.step(
                    (Operation<TestOrderContext, TestOrderContext>) (ctx, req) -> Outcome.rejected(Reason.of("INSUFFICIENT_FUNDS", "balance too low"))
            );

            FlowLoggingObserver observer = new FlowLoggingObserver();
            LocalExecutable<TestOrderContext, TestOrderContext> executable = Local.from(rejectedFlow)
                    .flowId("rejected-flow")
                    .flowVersion(1)
                    .observer(observer)
                    .compile();

            TestOrderContext context = new TestOrderContext();
            FlowResult<TestOrderContext> result = FlowContextHolder.runWith(context, () -> executable.run(context));

            Assert.assertTrue(result instanceof FlowResult.Completed);
            TraceNode root = observer.rootTraceNode();
            Assert.assertNotNull(root);
            Assert.assertEquals("REJECTED", root.getOutcome());
            Assert.assertTrue(root.getChildren().isEmpty());

        } finally {
            logHelper.stop();
        }
    }

    @Test
    public void testCustomProjectorAndBuilderOptions() throws Exception {
        TestLogHelper logHelper = TestLogHelper.start();
        try {
            FlowLoggingObserver observer = FlowLoggingObserver.builder()
                    .loggerNamePrefix("custom.prefix")
                    .contextProjector(ContextProjector.fields("orderId", "amount"))
                    .printStepLogs(true)
                    .printTreeSummary(false)
                    .build();

            Flow<TestOrderContext, TestOrderContext> flow = Flow.<TestOrderContext, TestOrderContext>step(
                    (ctx, req) -> Outcome.accepted(req)
            ).named("Single Step");
            LocalExecutable<TestOrderContext, TestOrderContext> executable = Local.from(flow)
                    .flowId("custom-projector-flow")
                    .flowVersion(1)
                    .observer(observer)
                    .compile();

            TestOrderContext context = new TestOrderContext();
            FlowResult<TestOrderContext> result = FlowContextHolder.runWith(context, () -> executable.run(context));

            Assert.assertTrue(result instanceof FlowResult.Completed);
            Assert.assertNotNull(observer.rootTraceNode());
            Assert.assertNull(observer.rootTraceNode("non-existent-id"));

            boolean hasOrderIdOnly = logHelper.allEvents().stream().anyMatch(e -> {
                Object ctx = e.get("context");
                return ctx != null && ctx.toString().contains("ORD-8888") && !ctx.toString().contains("mobile");
            });
            Assert.assertTrue("应当仅包含投影白名单字段", hasOrderIdOnly);

        } finally {
            logHelper.stop();
        }
    }

    @Test
    public void testTraceNodeModelDirect() {
        TraceNode node = new TraceNode("$/0", "TestNode");
        node.setStartTime(100L);
        node.setDurationMs(50L);
        node.setOutcome("ACCEPTED");
        node.setExtra("attempt=1");

        Assert.assertEquals("$/0", node.getPath());
        Assert.assertEquals("TestNode", node.getLabel());
        Assert.assertEquals(100L, node.getStartTime());
        Assert.assertEquals(50L, node.getDurationMs());
        Assert.assertEquals("ACCEPTED", node.getOutcome());
        Assert.assertEquals("attempt=1", node.getExtra());

        node.addChild(null);
        Assert.assertEquals(0, node.getChildren().size());

        TraceNode child = new TraceNode("$/0/0", "ChildNode");
        node.addChild(child);
        Assert.assertEquals(1, node.snapshotChildren().size());
        Assert.assertEquals("ChildNode", node.snapshotChildren().get(0).getLabel());
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class UserVerifyReq {
        private String userId;
        private String realName;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class PaymentResult {
        private String orderId;
        private Double amount;
    }

    @Test
    public void testHeterogeneousPipelineWithoutThreadLocal() {
        TestLogHelper logHelper = TestLogHelper.start();
        try {
            // 配置按类型路由投影器
            ContextProjector projector = ContextProjector.byType()
                    .bind(UserVerifyReq.class, (UserVerifyReq req) -> Collections.singletonMap("uid", req.getUserId()))
                    .bindFields(PaymentResult.class, "orderId", "amount")
                    .build();

            FlowLoggingObserver observer = FlowLoggingObserver.builder()
                    .contextProjector(projector)
                    .printStepLogs(true)
                    .printTreeSummary(true)
                    .build();

            // 构造异构流水线 Flow: UserVerifyReq -> PaymentResult
            Flow<UserVerifyReq, PaymentResult> pipelineFlow = Flow.<UserVerifyReq, PaymentResult>step(
                    (ctx, req) -> Outcome.accepted(new PaymentResult("ORD-PAY-123", 500.0))
            ).named("Payment Step");

            LocalExecutable<UserVerifyReq, PaymentResult> executable = Local.from(pipelineFlow)
                    .flowId("heterogeneous-flow")
                    .flowVersion(1)
                    .observer(observer)
                    .compile();

            // 直接调用 run，完全无需 FlowContextHolder.runWith
            UserVerifyReq inputReq = new UserVerifyReq("USER-777", "张三");
            FlowResult<PaymentResult> result = executable.run(inputReq);

            Assert.assertTrue(result instanceof FlowResult.Completed);
            PaymentResult finalPay = result.requireAccepted();
            Assert.assertEquals("ORD-PAY-123", finalPay.getOrderId());

            // 验证日志中自动捕获了第一步入参 UserVerifyReq (uid=USER-777)
            boolean foundUserReqLog = logHelper.allEvents().stream().anyMatch(e -> {
                Object ctx = e.get("context");
                return ctx != null && ctx.toString().contains("USER-777");
            });
            Assert.assertTrue("单步日志应当自动捕获并投影 UserVerifyReq", foundUserReqLog);

            // 验证日志中自动捕获了步骤产出的 PaymentResult (ORD-PAY-123, 500.0)
            boolean foundPayResultLog = logHelper.allEvents().stream().anyMatch(e -> {
                Object ctx = e.get("context");
                return ctx != null && ctx.toString().contains("ORD-PAY-123") && ctx.toString().contains("500.0");
            });
            Assert.assertTrue("单步完成日志应当自动捕获并投影 PaymentResult", foundPayResultLog);

        } finally {
            logHelper.stop();
        }
    }
}
