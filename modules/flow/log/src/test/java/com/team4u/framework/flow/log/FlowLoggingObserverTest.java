package com.team4u.framework.flow.log;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.log.support.TestLogHelper;
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;
import lombok.Data;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

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
            .then((ctx, req) -> {
                req.setStatus("PAID");
                return Outcome.accepted(req);
            }).named("Step 2: Pay");

            // 2. 构造日志观察者
            FlowLoggingObserver observer = FlowLoggingObserver.builder()
                    .loggerNamePrefix("flow.trace")
                    .printStepLogs(true)
                    .printTreeSummary(true)
                    .build();

            LocalExecutable<TestOrderContext, TestOrderContext> executable = Local.compile(
                    flow,
                    "order-test-flow",
                    1,
                    com.team4u.framework.flow.spi.OperationResolver.rejecting(),
                    observer,
                    null
            );

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

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            logHelper.stop();
        }
    }

    @Test
    public void testParallelFlowExecution() throws Exception {
        Branch<TestOrderContext, TestOrderContext> b1 = Branch.of("BranchA", (ctx, req) -> Outcome.accepted(req));
        Branch<TestOrderContext, TestOrderContext> b2 = Branch.of("BranchB", (ctx, req) -> Outcome.accepted(req));
        Flow<TestOrderContext, TestOrderContext> flow = Flow.parallel(b1, b2).join(results -> results.outcome(b1));

        FlowLoggingObserver observer = new FlowLoggingObserver();
        LocalExecutable<TestOrderContext, TestOrderContext> executable = Local.compile(
                flow, "parallel-flow", 1, com.team4u.framework.flow.spi.OperationResolver.rejecting(), observer, null
        );

        TestOrderContext context = new TestOrderContext();
        FlowResult<TestOrderContext> result = FlowContextHolder.runWith(context, () -> executable.run(context));

        Assert.assertTrue(result instanceof FlowResult.Completed);
        Assert.assertNotNull(observer.rootTraceNode());
    }
}
