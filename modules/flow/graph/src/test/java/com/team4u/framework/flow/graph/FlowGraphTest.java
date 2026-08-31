package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.Branch;
import com.team4u.framework.flow.Completion;
import com.team4u.framework.flow.Failure;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowDescription;
import com.team4u.framework.flow.Gate;
import com.team4u.framework.flow.Operation;
import com.team4u.framework.flow.OperationContext;
import com.team4u.framework.flow.Outcome;
import com.team4u.framework.flow.PersistentPolicy;
import com.team4u.framework.flow.Policy;
import com.team4u.framework.flow.PolicyContext;
import com.team4u.framework.flow.Reason;
import com.team4u.framework.flow.ResumePoint;
import com.team4u.framework.flow.Retry;
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

/** Tests for rendering the frozen Core static description model. */
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

    /** 返回 Object 路由键的 selector，用于测试任意类型 route key 的稳定渲染。 */
    public static final class ObjectSelect implements Operation<String, Object> {
        @Override
        public Outcome<Object> execute(OperationContext context, String input) {
            return Outcome.accepted((Object) input);
        }
    }

    /** 非 final 用户类型，重写 toString 并计数，断言渲染器从不调用它。 */
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
                        .retry(Retry.maxAttempts(2))
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
    public void mermaidHasFourIsolatedCompletionChannelsAndLifecycleTerminals() {
        String graph = FlowGraphs.mermaid().render(Flow.<String, String>step(Echo.class)
                .describe("channels"));

        for (String channel : new String[] {"accepted", "rejected", "skipped", "failed"}) {
            Assert.assertTrue(graph.contains("terminal_" + channel + "([\"COMPLETED &#124; "
                    + channel.toUpperCase() + "\"])"));
        }
        Assert.assertTrue(graph.contains("terminal_suspended([\"SUSPENDED\"])"));
        Assert.assertTrue(graph.contains("terminal_cancelled([\"CANCELLED\"])"));
        Assert.assertTrue(graph.contains("|ACCEPTED| terminal_accepted"));
        Assert.assertTrue(graph.contains("|REJECTED| terminal_rejected"));
        Assert.assertTrue(graph.contains("|SKIPPED| terminal_skipped"));
        Assert.assertTrue(graph.contains("|FAILED| terminal_failed"));
        Assert.assertFalse(graph.contains("|REJECTED| terminal_accepted"));
        Assert.assertFalse(graph.contains("|SKIPPED| terminal_accepted"));
        Assert.assertFalse(graph.contains("|FAILED| terminal_accepted"));
        Assert.assertTrue(graph.contains("flow_start -.->|CANCELLED| terminal_cancelled"));
    }

    @Test
    public void sequenceAdvancesOnlyAccepted() {
        String graph = FlowGraphs.mermaid().render(Flow.<String, String>step(Echo.class)
                .then(Echo.class).describe("sequence"));

        Assert.assertEquals(1, occurrences(graph, "-->|ACCEPTED| n"));
        Assert.assertFalse(graph.contains("-->|REJECTED| n"));
        Assert.assertFalse(graph.contains("-.->|SKIPPED| n"));
        Assert.assertFalse(graph.contains("-.->|FAILED| n"));
    }

    @Test
    public void fallbackRoutesOnlyItsConfiguredTrigger() {
        String firstApplicable = FlowGraphs.mermaid().render(Flow.firstApplicable(
                Flow.<String, String>skipped(reason("FIRST")), Flow.accepted("second"))
                .describe("first-applicable"));
        Assert.assertTrue(firstApplicable.contains("firstApplicable"));
        Assert.assertTrue(firstApplicable.contains("SKIPPED &#124; next applicable"));
        Assert.assertFalse(firstApplicable.contains("FAILED &#124; next applicable"));

        String recover = FlowGraphs.mermaid().render(Flow.<String, String>failed(
                Failure.of("BROKEN", "broken")).recoverWith(Flow.accepted("fixed"))
                .describe("recover"));
        Assert.assertTrue(recover.contains("recoverWith"));
        Assert.assertTrue(recover.contains("FAILED &#124; recover"));
        Assert.assertFalse(recover.contains("SKIPPED &#124; recover"));
        Assert.assertFalse(recover.contains("REJECTED &#124; recover"));
    }

    @Test
    public void routeRendersCasesOtherwiseAndNoMatchSkipped() {
        Flow<String, String> withOtherwise = Flow.route(Select.class)
                .caseOf("A|B", Flow.accepted("case"))
                .otherwise(Flow.rejected(reason("DEFAULT")));
        String otherwise = FlowGraphs.mermaid().render(withOtherwise.describe("otherwise"));
        Assert.assertTrue(otherwise.contains("case=A&#124;B"));
        Assert.assertTrue(otherwise.contains("ACCEPTED &#124; otherwise"));
        Assert.assertFalse(otherwise.contains("NO MATCH &#124; SKIPPED"));

        Flow<String, String> withoutOtherwise = Flow.route(Select.class)
                .caseOf("A", Flow.accepted("case"))
                .withoutOtherwise();
        String noMatch = FlowGraphs.mermaid().render(withoutOtherwise.describe("no-match"));
        Assert.assertTrue(noMatch.contains("no-match=SKIPPED"));
        Assert.assertTrue(noMatch.contains("NO MATCH &#124; SKIPPED"));
        Assert.assertTrue(noMatch.contains("ACCEPTED &#124; no match"));
        Assert.assertTrue(noMatch.contains("|SKIPPED| terminal_skipped"));
    }

    @Test
    public void parallelShowsBranchTokensWaitAllAndExplicitJoin() {
        Flow<String, String> flow = Flow.parallel(
                Branch.of("left|token", Flow.<String, String>accepted("left")),
                Branch.of("right", Flow.<String, String>failed(Failure.of("R", "right"))))
                .join(results -> Outcome.accepted("joined"));
        String graph = FlowGraphs.mermaid().render(flow.describe("parallel"));

        Assert.assertTrue(graph.contains("branch=left&#124;token"));
        Assert.assertTrue(graph.contains("BRANCH COMPLETE &#124; token=left&#124;token"));
        Assert.assertTrue(graph.contains("BRANCH COMPLETE &#124; token=right"));
        Assert.assertEquals(2, occurrences(graph, "|wait-all|"));
        Assert.assertTrue(graph.contains("WAIT ALL &#124; branches=2"));
        Assert.assertTrue(graph.contains("JOIN &#124; static outcome contract"));
        Assert.assertTrue(graph.contains("|all branches complete|"));
    }

    @Test
    public void awaitShowsSuspendedResumedAndCancellationLifecycle() {
        String graph = FlowGraphs.mermaid().render(Flow.<String>identity()
                .await(ResumePoint.<String>named("manual-review"))
                .describe("await"));

        Assert.assertTrue(graph.contains("SUSPENDED &#124; resume=manual-review"));
        Assert.assertTrue(graph.contains("RESUMED &#124; resume=manual-review"));
        Assert.assertTrue(graph.contains("|resume signal|"));
        Assert.assertTrue(graph.contains("|SUSPENDED| terminal_suspended"));
        Assert.assertTrue(graph.contains("|CANCELLED| terminal_cancelled"));
    }

    @Test
    public void rendersAllControlKindsAndConfigurationTypesWithoutValues() {
        FlowDescription retry = Flow.<String, String>step(Echo.class).retry(
                Retry.maxAttempts(7).withBackoff(Duration.ofSeconds(11))).describe("retry");
        FlowDescription timeout = Flow.<String>identity().timeout(
                Duration.ofSeconds(29)).describe("timeout");
        FlowDescription policy = Flow.<String>identity().policy(
                TestPolicy.class, "policy-q", value -> value).describe("policy");
        FlowDescription persistent = Flow.<String>identity().persistentPolicy(
                TestPersistentPolicy.class, "persistent-q", value -> value).describe("persistent");

        String text = FlowGraphs.text().render(retry)
                + FlowGraphs.text().render(timeout)
                + FlowGraphs.text().render(policy)
                + FlowGraphs.text().render(persistent);
        Assert.assertTrue(text.contains("control=RETRY config=maxAttempts=7,backoff=11s0ns"));
        Assert.assertTrue(text.contains("control=TIMEOUT config=timeout=29s0ns"));
        Assert.assertTrue(text.contains("control=POLICY config=<none>"));
        Assert.assertTrue(text.contains("control=PERSISTENT_POLICY config=<none>"));
        Assert.assertTrue(text.contains("contract=" + TestPolicy.class.getName()
                + " qualifier=\"policy-q\""));
        Assert.assertFalse(text.contains("PT11S"));
        Assert.assertFalse(text.contains("PT29S"));

        String retryGraph = FlowGraphs.mermaid().render(retry);
        Assert.assertTrue(retryGraph.contains("FAILED &#124; retry while configured"));
        Assert.assertTrue(retryGraph.contains(
                "control=RETRY &#124; config=maxAttempts=7,backoff=11s0ns"));
        String timeoutGraph = FlowGraphs.mermaid().render(timeout);
        Assert.assertTrue(timeoutGraph.contains("config=timeout=29s0ns"));
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
        Assert.assertTrue(count >= 4);
        Assert.assertEquals(3, occurrences(first, "label=same"));
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
        Assert.assertEquals(5000, occurrences(graph, "SEQUENCE &#124; path="));
        Assert.assertTrue(graph.contains("INVOKE &#124; path="));
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
        Matcher imports = Pattern.compile("import com\\.team4u\\.framework\\.flow\\.([^;]+);")
                .matcher(code);
        while (imports.find()) {
            String imported = imports.group(1);
            Assert.assertTrue("Unexpected Core dependency: " + imported,
                    "FlowDescription".equals(imported)
                            || "NodeDescription".equals(imported)
                            || "BindingDescriptor".equals(imported)
                            || "Retry".equals(imported));
        }
    }

    @Test
    public void parallelBranchCancellationNeverReachesJoin() {
        // 分支含 TIMEOUT control：分支会产生 CANCELLED/SUSPENDED 出口。
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("hot", Flow.<String>identity()
                        .timeout(Duration.ofMillis(100)).named("hot-body")),
                Branch.of("cold", Flow.<String, String>accepted("cold")))
                .join(results -> Outcome.accepted("joined"));
        String graph = FlowGraphs.mermaid().render(flow.describe("parallel-cancel"));

        Map<String, List<String>> edges = parseEdges(graph);
        String join = findNodeByLabel(graph, "JOIN &#124; static outcome contract");
        Assert.assertNotNull("Missing JOIN node\n" + graph, join);
        String cancel = findNodeByLabel(graph, "CANCEL &#124; branches=2");
        Assert.assertNotNull("Missing CANCEL node\n" + graph, cancel);

        // join 只能经由 wait-all 的业务完成链到达。
        Assert.assertTrue(graph.contains("|all branches complete| " + join));
        Assert.assertFalse("CANCELLED must not flow into join: \n" + graph,
                reaches(edges, cancel, join));

        // 所有标签含 CANCELLED 的节点均不可达 join。
        for (String cancelled : findNodesByLabelContains(graph, "CANCELLED")) {
            Assert.assertFalse("CANCELLED node " + cancelled + " reaches join: \n" + graph,
                    reaches(edges, cancelled, join));
        }
        // SUSPENDED 同样不可达 join。
        for (String suspended : findNodesByLabelContains(graph, "SUSPENDED")) {
            Assert.assertFalse("SUSPENDED node " + suspended + " reaches join: \n" + graph,
                    reaches(edges, suspended, join));
        }
        // 业务四态仍应经 BRANCH COMPLETE 汇入 wait-all→join。
        Assert.assertEquals(2, occurrences(graph, "|wait-all|"));
        Assert.assertTrue(graph.contains("WAIT ALL &#124; branches=2"));
        // Parallel 自身取消经由 cancel 节点到达 CANCELLED 终结点。
        Assert.assertTrue(graph.contains("-.->|CANCELLED| terminal_cancelled"));
    }

    @Test
    public void parallelBranchCancelledExitsBypassWaitAll() {
        // 分支直接是 CONTROL(RETRY)：其 CANCELLED 出口必须绕过 wait-all/join。
        Flow<String, String> flow = Flow.<String>parallel(
                Branch.of("a", Flow.<String>identity().retry(Retry.maxAttempts(2))),
                Branch.of("b", Flow.<String>identity().retry(Retry.maxAttempts(3))))
                .join(results -> Outcome.accepted("joined"));
        String graph = FlowGraphs.mermaid().render(flow.describe("parallel-retry"));

        Map<String, List<String>> edges = parseEdges(graph);
        String join = findNodeByLabel(graph, "JOIN &#124; static outcome contract");
        for (String node : findNodesByLabelContains(graph, "CANCEL ")) {
            Assert.assertFalse("cancel node " + node + " reaches join\n" + graph,
                    reaches(edges, node, join));
        }
        Assert.assertEquals(2, occurrences(graph, "|wait-all|"));
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
                graph.contains("case=" + exact));
        // 非 final 类型渲染为转义后的固定占位符，且不携带任何类名。
        Assert.assertTrue(graph.contains("case=&lt;opaque&gt;"));
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

        Assert.assertTrue(graph.contains("case=&lt;opaque&gt;"));
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
        text = null;

        String graph = FlowGraphs.mermaid().render(description);
        Assert.assertEquals(5000, occurrences(graph, "&#124; control=TIMEOUT"));
        Assert.assertEquals(5000, occurrences(graph, "config=timeout=1s0ns"));
        // 嵌套 timeout 的 FAILED/CANCELLED 出口逐层声明：body 传播 1 条 + 每层自身 1 条；
        // CANCELLED 另有 flow_start 的常量取消边。
        Assert.assertEquals(5001, occurrences(graph, "-.->|FAILED| terminal_failed"));
        Assert.assertEquals(5001, occurrences(graph, "-.->|CANCELLED| terminal_cancelled"));
    }

    @Test
    public void configurationSummariesAreStableInBothRenderers() {
        FlowDescription retry = Flow.<String, String>step(Echo.class).retry(
                Retry.maxAttempts(7).withBackoff(
                        Duration.ofSeconds(11).plusNanos(500))).describe("retry-summary");
        FlowDescription timeout = Flow.<String>identity().timeout(
                Duration.ofSeconds(29).plusNanos(25)).describe("timeout-summary");

        Assert.assertTrue(FlowGraphs.text().render(retry)
                .contains("control=RETRY config=maxAttempts=7,backoff=11s500ns"));
        Assert.assertTrue(FlowGraphs.text().render(timeout)
                .contains("control=TIMEOUT config=timeout=29s25ns"));
        Assert.assertTrue(FlowGraphs.mermaid().render(retry).contains(
                "control=RETRY &#124; config=maxAttempts=7,backoff=11s500ns"));
        Assert.assertTrue(FlowGraphs.mermaid().render(timeout).contains(
                "control=TIMEOUT &#124; config=timeout=29s25ns"));
    }

    private static Reason reason(String code) {
        return Reason.of(code, code);
    }

    /** 解析 Mermaid 边：source -->|label| target 或 source -.->|label| target。 */
    private static Map<String, List<String>> parseEdges(String graph) {
        Map<String, List<String>> edges = new HashMap<String, List<String>>();
        Pattern pattern = Pattern.compile(
                "^\\s*(\\S+)\\s+-\\.?->(?:\\|[^\\n]*\\|)?\\s*(\\S+)\\s*$",
                Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(graph);
        while (matcher.find()) {
            String source = matcher.group(1);
            String target = matcher.group(2);
            List<String> targets = edges.get(source);
            if (targets == null) {
                targets = new ArrayList<String>();
                edges.put(source, targets);
            }
            targets.add(target);
        }
        return edges;
    }

    /** 从起始节点 BFS 可达性检查（存在环时安全终止）。 */
    private static boolean reaches(Map<String, List<String>> edges, String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        List<String> frontier = new ArrayList<String>();
        Set<String> visited = new HashSet<String>();
        frontier.add(from);
        visited.add(from);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<String>();
            for (String node : frontier) {
                List<String> targets = edges.get(node);
                if (targets == null) {
                    continue;
                }
                for (String target : targets) {
                    if (target.equals(to)) {
                        return true;
                    }
                    if (visited.add(target)) {
                        next.add(target);
                    }
                }
            }
            frontier = next;
        }
        return false;
    }

    /** 按 label 全文查找节点 id：形如 {@code n12(["LABEL"])}，label 需传转义后的文本。 */
    private static String findNodeByLabel(String graph, String label) {
        for (String id : findNodesByLabelContains(graph, label)) {
            return id;
        }
        return null;
    }

    /** 查找 label 含指定片段（应为转义后文本）的所有节点 id。 */
    private static List<String> findNodesByLabelContains(String graph, String fragment) {
        List<String> ids = new ArrayList<String>();
        Pattern pattern = Pattern.compile(
                "^\\s*(\\S+)(?:\\[\\[|\\[\\{|\\[|\\{|\\(\\[)\"([^\"]*)\"",
                Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(graph);
        while (matcher.find()) {
            if (matcher.group(2).contains(fragment)) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
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
}
