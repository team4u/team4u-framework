package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.model.Resumed;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流程图渲染器单元测试。
 */
public class FlowGraphTest {

    public static final class Echo implements Operation<String, String> {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input);
        }
    }

    public static final class Select implements Operation<String, String> {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input);
        }
    }

    public static final class ObjectSelect implements Operation<String, Object> {
        @Override
        public Outcome<Object> execute(OperationContext context, String input) {
            return Outcome.accepted((Object) input);
        }
    }

    public static final class CountingKey {
        final String secret;
        int toStringCalls;

        CountingKey(String secret) {
            this.secret = secret;
        }

        @Override
        public String toString() {
            toStringCalls++;
            return "COUNTING-" + secret;
        }
    }

    public static final class TestPolicy implements Policy<String> {
        @Override
        public Gate before(PolicyContext context, String key) {
            return Gate.proceed();
        }
    }

    public static final class TestPersistentPolicy implements PersistentPolicy<String, String> {
        @Override
        public String initialState(String key) {
            return key;
        }

        @Override
        public Before<String> before(PolicyContext context, String key, String state) {
            return null;
        }

        @Override
        public After<String> after(PolicyContext context, String key, String state,
                                    Completion completion) {
            return null;
        }
    }

    @Test
    public void rendersAllEightDescriptionKinds() {
        Branch<String, String> left = Branch.of("left", Flow.accepted("left-value"));
        Branch<String, String> right = Branch.of("right", Flow.skipped(reason("RIGHT_SKIP")));
        Flow<String, String> parallel = Flow.parallel(left, right)
                .join(results -> Outcome.accepted("joined"));
        Flow<String, String> route = Flow.route(Select.class, "selector-q")
                .caseOf("A", Flow.accepted("a"))
                .otherwise(Flow.rejected(reason("OTHER")));
        Flow<String, ?> flow = Flow.scope("root-scope",
                Flow.<String, String>step(Echo.class, "operation-q")
                        .then(route)
                        .then(Flow.firstApplicable(
                                Flow.skipped(reason("SKIP")), Flow.accepted("usable")))
                        .then(parallel)
                        .timeout(Duration.ofSeconds(3))
                        .recoverWith(Flow.accepted("recovered")))
                .await(ResumePoint.<String>named("approval"))
                .named("all-kinds");

        String text = FlowGraphs.text().render(flow.describe("all-eight"));
        for (String kind : new String[] {"INVOKE", "SEQUENCE", "ROUTE", "FALLBACK",
                "PARALLEL", "AWAIT", "CONTROL", "COMPLETE"}) {
            Assert.assertTrue("Missing " + kind + "\n" + text, text.contains("kind=" + kind));
        }
        Assert.assertTrue(text.contains("scope=\"root-scope\""));
        Assert.assertTrue(text.contains("binding=OPERATION contract=" + Select.class.getName()));
        Assert.assertTrue(text.contains("qualifier=\"selector-q\""));
        Assert.assertTrue(text.contains("resume=\"approval\""));
    }

    @Test
    public void mermaidRendersCleanFlowAndStyles() {
        String graph = FlowGraphs.mermaid().render(Flow.<String, String>step(Echo.class)
                .describe("channels"));

        Assert.assertTrue(graph.contains("flowchart TD"));
        Assert.assertTrue(graph.contains("flow_start([\"开始: channels\"])"));
        Assert.assertTrue(graph.contains("flow_end([\"✅ 结束 (ACCEPTED)\"])"));
        Assert.assertTrue(graph.contains("classDef startEnd"));
        Assert.assertTrue(graph.contains("classDef actionNode"));
        Assert.assertTrue(graph.contains("class flow_start,flow_end startEnd"));
    }

    @Test
    public void sequenceAdvancesThroughSteps() {
        String graph = FlowGraphs.mermaid().render(Flow.<String, String>step(Echo.class).named("Step1")
                .then(Flow.<String, String>step(Echo.class).named("Step2"))
                .describe("sequence"));

        Assert.assertTrue(graph.contains("Step1"));
        Assert.assertTrue(graph.contains("Step2"));
        Assert.assertTrue(graph.contains("--> n2"));
        Assert.assertTrue(graph.contains("flow_start --> n1"));
        Assert.assertTrue(graph.contains("n2 --> flow_end"));
    }

    @Test
    public void fallbackRoutesOnlyItsConfiguredTrigger() {
        String firstApplicable = FlowGraphs.mermaid().render(Flow.firstApplicable(
                Flow.<String, String>skipped(reason("FIRST")), Flow.accepted("second"))
                .describe("first-applicable"));
        Assert.assertTrue(firstApplicable.contains("SKIPPED 备用"));

        String recover = FlowGraphs.mermaid().render(Flow.<String, String>failed(
                Failure.of("BROKEN", "broken")).recoverWith(Flow.accepted("fixed"))
                .describe("recover"));
        Assert.assertTrue(recover.contains("FAILED 降级"));
    }

    @Test
    public void routeRendersCasesOtherwiseAndNoMatchSkipped() {
        Flow<String, String> withOtherwise = Flow.route(Select.class)
                .caseOf("A|B", Flow.accepted("case"))
                .otherwise(Flow.rejected(reason("DEFAULT")));
        String otherwise = FlowGraphs.mermaid().render(withOtherwise.describe("otherwise"));
        Assert.assertTrue(otherwise.contains("|A&#124;B|"));
        Assert.assertTrue(otherwise.contains("|otherwise|"));
        Assert.assertFalse(otherwise.contains("未匹配"));

        Flow<String, String> withoutOtherwise = Flow.route(Select.class)
                .caseOf("A", Flow.accepted("case"))
                .withoutOtherwise();
        String noMatch = FlowGraphs.mermaid().render(withoutOtherwise.describe("no-match"));
        Assert.assertTrue(noMatch.contains("|A|"));
        Assert.assertTrue(noMatch.contains("未匹配 (SKIPPED)"));
        Assert.assertTrue(noMatch.contains("|no match|"));
    }

    @Test
    public void parallelShowsBranchTokensAndJoin() {
        Flow<String, String> flow = Flow.parallel(
                Branch.of("left|token", Flow.<String, String>accepted("left")),
                Branch.of("right", Flow.<String, String>failed(Failure.of("R", "right"))))
                .join(results -> Outcome.accepted("joined"));
        String graph = FlowGraphs.mermaid().render(flow.describe("parallel"));

        Assert.assertTrue(graph.contains("并行: 并行分发"));
        Assert.assertTrue(graph.contains("|left&#124;token|"));
        Assert.assertTrue(graph.contains("|right|"));
        Assert.assertTrue(graph.contains("合并 (Join)"));
    }

    @Test
    public void awaitShowsSuspendedAndSignal() {
        String graph = FlowGraphs.mermaid().render(Flow.<String>identity()
                .await(ResumePoint.<String>named("manual-review"))
                .describe("await"));

        Assert.assertTrue(graph.contains("⏳ 挂起等待: manual-review"));
        Assert.assertTrue(graph.contains("awaitNode"));
    }

    @Test
    public void rendersAllControlKindsAndConfigurationTypesWithoutValues() {
        FlowDescription timeout = Flow.<String>identity().timeout(
                Duration.ofSeconds(29)).describe("timeout");
        FlowDescription policy = Flow.<String>identity().policy(
                TestPolicy.class, "policy-q", value -> value).describe("policy");
        FlowDescription persistent = Flow.<String>identity().persistentPolicy(
                TestPersistentPolicy.class, "persistent-q", value -> value).describe("persistent");

        String text = FlowGraphs.text().render(timeout)
                + FlowGraphs.text().render(policy)
                + FlowGraphs.text().render(persistent);
        Assert.assertTrue(text.contains("control=TIMEOUT config=timeout=29s0ns"));
        Assert.assertTrue(text.contains("control=POLICY config=<none>"));
        Assert.assertTrue(text.contains("control=PERSISTENT_POLICY config=<none>"));

        String timeoutGraph = FlowGraphs.mermaid().render(timeout);
        Assert.assertTrue(timeoutGraph.contains("⏱️ 29s"));

        String policyGraph = FlowGraphs.mermaid().render(policy);
        Assert.assertTrue(policyGraph.contains("🛡️ policy-q"));

        String persistentGraph = FlowGraphs.mermaid().render(persistent);
        Assert.assertTrue(persistentGraph.contains("💾 persistent-q"));
    }

    @Test
    public void completeNodesExposeIdentityAndEachFixedOutcomeWithoutValues() {
        FlowDescription[] descriptions = new FlowDescription[] {
                Flow.<String>identity().describe("identity"),
                Flow.<String, String>accepted("SECRET_OUTPUT").describe("accepted"),
                Flow.<String, String>rejected(reason("SECRET_REJECT")).describe("rejected"),
                Flow.<String, String>skipped(reason("SECRET_SKIP")).describe("skipped"),
                Flow.<String, String>failed(Failure.of("SECRET_FAIL", "secret"))
                        .describe("failed")
        };
        StringBuilder text = new StringBuilder();
        for (FlowDescription description : descriptions) {
            text.append(FlowGraphs.text().render(description));
        }
        for (String completion : new String[] {"IDENTITY", "ACCEPTED", "REJECTED", "SKIPPED", "FAILED"}) {
            Assert.assertTrue(text.toString().contains("complete=" + completion));
        }
        Assert.assertFalse(text.toString().contains("SECRET_"));
    }

    @Test
    public void safelyEscapesMetadataRouteKeysAndMermaidSyntax() {
        String hostile = "quote\" slash\\ newline\npipe| []{}()<>`&";
        Flow<String, String> flow = Flow.route(Select.class, hostile)
                .caseOf(hostile, Flow.<String, String>accepted("hidden"))
                .withoutOtherwise()
                .named(hostile);
        String graph = FlowGraphs.mermaid().render(flow.describe(hostile));
        String text = FlowGraphs.text().render(flow.describe(hostile));

        Assert.assertTrue(graph.contains("&quot;"));
        Assert.assertTrue(graph.contains("&#92;"));
        Assert.assertTrue(graph.contains("<br/>"));
        Assert.assertTrue(graph.contains("&#124;"));
        Assert.assertTrue(graph.contains("&#91;"));
        Assert.assertTrue(graph.contains("&#123;"));
        Assert.assertTrue(graph.contains("&#40;"));
        Assert.assertTrue(graph.contains("&lt;&gt;&#96;&amp;"));
        Assert.assertFalse(graph.contains("newline\npipe"));
        Assert.assertTrue(text.contains("quote\\\" slash\\\\ newline\\npipe\\|"));
        Assert.assertFalse(text.contains("hidden"));
    }

    @Test
    public void repeatedLabelsHaveCollisionFreeIdsAndRenderingIsDeterministic() {
        Flow<String, String> repeated = Flow.<String, String>step(Echo.class).named("same")
                .then(Flow.<String, String>step(Echo.class).named("same"))
                .then(Flow.<String, String>step(Echo.class).named("same"));
        FlowDescription description = repeated.describe("deterministic");
        String first = FlowGraphs.mermaid().render(description);
        String second = FlowGraphs.mermaid().render(description);
        Assert.assertEquals(first, second);
        Assert.assertFalse(first.contains("Lambda"));
        Assert.assertFalse(first.matches("(?s).*@[0-9a-fA-F]{4,}.*"));

        Matcher declarations = Pattern.compile("^\\s*(n\\d+)(?:\\[|\\{|\\()", Pattern.MULTILINE)
                .matcher(first);
        Set<String> ids = new HashSet<String>();
        int count = 0;
        while (declarations.find()) {
            count++;
            Assert.assertTrue("Duplicate Mermaid id " + declarations.group(1),
                    ids.add(declarations.group(1)));
        }
        Assert.assertTrue(count >= 3);
        Assert.assertEquals(3, occurrences(first, "same"));
    }

    @Test
    public void textIsCompactAndOmitsRouteBusinessConstantsAndImplementations() {
        Flow<String, String> flow = Flow.route(Select.class, "route-q")
                .caseOf("SECRET_CASE_VALUE", Flow.<String, String>accepted("SECRET_OUTPUT"))
                .withoutOtherwise();
        String text = FlowGraphs.text().render(flow.describe("compact"));

        Assert.assertTrue(text.contains("path=\"$\" kind=ROUTE label=<none> routes=1"));
        Assert.assertTrue(text.contains("path=\"$/selector\" kind=INVOKE"));
        Assert.assertTrue(text.contains("contract=" + Select.class.getName()));
        Assert.assertFalse(text.contains("implementation"));
        Assert.assertFalse(text.contains("SECRET_CASE_VALUE"));
        Assert.assertFalse(text.contains("SECRET_OUTPUT"));
        for (String line : text.split("\\n")) {
            Assert.assertFalse("Text output must stay one line per node", line.contains("\n"));
        }
    }

    @Test
    public void bothRenderersHandleFiveThousandNestedScopesIteratively() {
        Flow<String, String> current = Flow.step(Echo.class);
        for (int index = 0; index < 5000; index++) {
            current = Flow.scope("scope-" + index, current);
        }
        FlowDescription description = current.describe("deep");

        String text = FlowGraphs.text().render(description);
        Assert.assertEquals(5000, occurrences(text, " kind=SEQUENCE "));
        Assert.assertTrue(text.contains(" kind=INVOKE "));
        text = null;

        String graph = FlowGraphs.mermaid().render(description);
        Assert.assertTrue(graph.contains("flowchart TD"));
        Assert.assertTrue(graph.contains("Echo"));
    }

    @Test
    public void productionDependsOnlyOnStaticDescriptionSurface() throws IOException {
        Path sourceRoot = Paths.get(System.getProperty("basedir"), "src", "main", "java",
                "com", "team4u", "framework", "flow", "graph");
        Assert.assertTrue("Missing production source root: " + sourceRoot, Files.isDirectory(sourceRoot));
        StringBuilder sources = new StringBuilder();
        for (String file : new String[] {"FlowGraphRenderer.java", "FlowGraphs.java",
                "MermaidFlowGraphRenderer.java", "TextFlowGraphRenderer.java"}) {
            sources.append(new String(Files.readAllBytes(sourceRoot.resolve(file)),
                    StandardCharsets.UTF_8));
        }
        String code = sources.toString();
        Assert.assertFalse(code.contains("import com.team4u.framework.flow.Flow;"));
        Assert.assertFalse(code.contains(".project("));
        Assert.assertFalse(code.contains("ExecutableFlowVisitor"));
        Assert.assertFalse(code.contains("Step"));
        Assert.assertFalse(code.contains("Guard"));
        Assert.assertFalse(code.contains("Choose"));
        Assert.assertFalse(code.contains("Subflow"));
        Assert.assertFalse(code.contains("Recover"));
        Assert.assertFalse(code.contains("Ensure"));
        Assert.assertFalse(code.contains("STOPPED"));
        Matcher imports = Pattern.compile("import com\\.team4u\\.framework\\.flow\\.(?:[a-z0-9_]+\\.)?([^;]+);")
                .matcher(code);
        while (imports.find()) {
            String imported = imports.group(1);
            Assert.assertTrue("Unexpected Core dependency: " + imported,
                    "FlowDescription".equals(imported)
                            || "NodeDescription".equals(imported)
                            || "BindingDescriptor".equals(imported)
                            || "ParallelBranchDescription".equals(imported)
                            || "RouteCaseDescription".equals(imported)
                            || "NodeDescriptor".equals(imported)
                            || "Outcome".equals(imported)
                            || "Retry".equals(imported));
        }
    }

    @Test
    public void bigIntegerRouteKeyRendersExactlyAndOpaqueNeverCallsToString() {
        BigInteger exact = new BigInteger("123456789012345678901234567890");
        CountingKey counting = new CountingKey("SECRETS");
        Flow<String, String> flow = Flow.<String, Object>route(ObjectSelect.class, "obj-q")
                .caseOf((Object) exact, Flow.<String, String>accepted("big"))
                .caseOf((Object) counting, Flow.<String, String>accepted("counted"))
                .withoutOtherwise();
        String graph = FlowGraphs.mermaid().render(flow.describe("route-keys"));

        Assert.assertTrue("BigInteger exact value missing\n" + graph,
                graph.contains("|" + exact + "|"));
        Assert.assertTrue(graph.contains("|&lt;opaque&gt;|"));
        Assert.assertFalse("CountingKey class name leaked: \n" + graph,
                graph.contains("CountingKey"));
        Assert.assertFalse("CountingKey toString leaked: \n" + graph,
                graph.contains("COUNTING-"));
        Assert.assertFalse(graph.contains("SECRETS"));
        Assert.assertEquals("toString must never be invoked", 0, counting.toStringCalls);
    }

    @Test
    public void lambdaRouteKeyRendersAsOpaqueWithoutLambdaClassName() {
        java.util.function.Function<String, String> lambda = value -> value + "!";
        Flow<String, Object> flow = Flow.<String, Object>route(ObjectSelect.class)
                .caseOf((Object) lambda, Flow.<String, Object>accepted("hidden"))
                .withoutOtherwise();
        String graph = FlowGraphs.mermaid().render(flow.describe("lambda-key"));

        Assert.assertTrue(graph.contains("|&lt;opaque&gt;|"));
        Assert.assertFalse("Lambda class name leaked\n" + graph, graph.contains("Lambda"));
        Assert.assertFalse("Synthetic identity leaked\n" + graph, graph.contains("0x"));
    }

    @Test
    public void fiveThousandNestedTimeoutsRenderWithinLinearCost() {
        Flow<String, String> current = Flow.step(Echo.class);
        for (int index = 0; index < 5000; index++) {
            current = current.timeout(Duration.ofSeconds(1));
        }
        FlowDescription description = current.describe("deep-timeouts");

        String text = FlowGraphs.text().render(description);
        Assert.assertEquals(5000, occurrences(text, "control=TIMEOUT"));

        String graph = FlowGraphs.mermaid().render(description);
        Assert.assertTrue(graph.contains("flowchart TD"));
        Assert.assertTrue(graph.contains("Echo"));
    }

    @Test
    public void configurationSummariesAreStableInBothRenderers() {
        FlowDescription timeout = Flow.<String>identity().timeout(
                Duration.ofSeconds(29).plusNanos(25)).describe("timeout-summary");

        Assert.assertTrue(FlowGraphs.text().render(timeout)
                .contains("control=TIMEOUT config=timeout=29s25ns"));
        Assert.assertTrue(FlowGraphs.mermaid().render(timeout).contains("⏱️ 29s25ns"));
    }

    private static Reason reason(String code) {
        return Reason.of(code, code);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    public static interface RiskCheckOperation extends Operation<String, String> {}
    public static interface RiskRouter extends Operation<String, String> {}
    public static interface PassAuditOperation extends Operation<Resumed<String, String>, String> {}
    public static interface LockInventoryOperation extends Operation<String, String> {}
    public static interface LockCouponOperation extends Operation<String, String> {}
    public static interface ChargePaymentOperation extends Operation<String, String> {}
    public static interface BackupPaymentOperation extends Operation<Recovery<String>, String> {}
    public static interface IssueReceiptOperation extends Operation<String, String> {}

    @Test
    public void printRealisticBusinessFlow() {
        Branch<String, String> inventoryBranch = Branch.of("lock-inventory",
                Flow.<String, String>step(LockInventoryOperation.class, "stock-service")
                        .timeout(Duration.ofSeconds(2))
                        .named("库存预占"));

        Branch<String, String> couponBranch = Branch.of("lock-coupon",
                Flow.<String, String>step(LockCouponOperation.class, "coupon-service")
                        .named("卡券锁定"));

        Flow<String, String> parallelLock = Flow.parallel(inventoryBranch, couponBranch)
                .join(results -> Outcome.accepted("resources-locked"))
                .named("并行资源锁定");

        Flow<String, String> manualAuditFlow = Flow.<String>identity()
                .await(ResumePoint.<String>named("manual-audit"))
                .then(Flow.step(PassAuditOperation.class, "audit-handler"))
                .named("高风险人工审核");

        Flow<String, String> riskRoute = Flow.route(RiskRouter.class, "risk-router")
                .caseOf("LOW", Flow.<String>identity().named("低风险直通"))
                .caseOf("HIGH", manualAuditFlow)
                .otherwise(Flow.<String, String>rejected(Reason.of("HIGH_RISK_REJECT", "高风险直接阻断")));

        Flow<String, String> paymentStep = Flow.<String, String>step(ChargePaymentOperation.class, "main-gateway")
                .named("主通道支付扣款")
                .timeout(Duration.ofSeconds(5))
                .recoverWith(Flow.<Recovery<String>, String>step(BackupPaymentOperation.class, "backup-gateway").named("备用通道降级"));

        Flow<String, String> orderFlow = Flow.scope("order-checkout-process",
                Flow.<String, String>step(RiskCheckOperation.class, "risk-checker").named("前置风控拦截")
                        .then(riskRoute)
                        .then(parallelLock)
                        .then(paymentStep)
                        .then(Flow.<String, String>step(IssueReceiptOperation.class, "receipt-service").named("生成出货单据"))
        ).named("order-fulfillment-flow");

        FlowDescription desc = orderFlow.describe("order-fulfillment-flow");
        String mermaid = FlowGraphs.mermaid().render(desc);
        String text = FlowGraphs.text().render(desc);

        Assert.assertNotNull(mermaid);
        Assert.assertNotNull(text);
        Assert.assertTrue(mermaid.contains("flow_start"));
        Assert.assertTrue(mermaid.contains("flow_end"));
        Assert.assertTrue(mermaid.contains("库存预占 ⏱️ 2s"));
        Assert.assertTrue(mermaid.contains("主通道支付扣款 ⏱️ 5s"));
        Assert.assertTrue(mermaid.contains("FAILED 降级"));
        Assert.assertTrue(mermaid.contains("合并 (Join)"));
        Assert.assertTrue(text.contains("flow id=\"order-fulfillment-flow\""));
    }
}
