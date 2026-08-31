package com.team4u.framework.flow.boundary;

import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.spi.ControlKind;
import com.team4u.framework.flow.spi.ExecutableBinding;
import com.team4u.framework.flow.spi.ExecutableFlowVisitor;
import com.team4u.framework.flow.spi.ExecutableParallelBranch;
import com.team4u.framework.flow.spi.ExecutableRouteCase;
import com.team4u.framework.flow.spi.FallbackTrigger;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.spi.NodeDescriptor;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.spi.OperationResolver;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.ParallelResults;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.Resumed;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 位于独立外部包（com.team4u.framework.flow.boundary）的边界与契约测试：
 * 证明不依赖反射、包私有访问或 split-package 即可通过公开 SPI 获取全部执行拓扑与合同。
 */
public class ExternalFlowBoundaryTest {

    public interface NumberOp extends Operation<String, Integer> { }
    public static final class NumberOpImpl implements NumberOp {
        @Override
        public Outcome<Integer> execute(OperationContext context, String input) {
            return Outcome.accepted(input.length());
        }
    }

    public interface StringOp extends Operation<Integer, String> { }
    public static final class StringOpImpl implements StringOp {
        @Override
        public Outcome<String> execute(OperationContext context, Integer input) {
            return Outcome.accepted("num:" + input);
        }
    }

    public interface RouteSelector extends Operation<String, String> { }
    public static final class RouteSelectorImpl implements RouteSelector {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input.startsWith("A") ? "A" : "B");
        }
    }

    public static final class SamplePolicy implements Policy<String> {
        @Override
        public Gate before(PolicyContext context, String key) {
            return Gate.proceed();
        }
    }

    public static final class SamplePersistentPolicy implements PersistentPolicy<String, Integer> {
        @Override
        public Integer initialState(String key) { return 0; }
        @Override
        public Before<Integer> before(PolicyContext context, String key, Integer state) {
            return PersistentPolicy.proceed(state + 1);
        }
        @Override
        public After<Integer> after(PolicyContext context, String key, Integer state, Completion completion) {
            return PersistentPolicy.returning(state);
        }
    }

    @Test
    public void publicSpiExportsAllExecutionContractsWithoutReflectionOrPackagePrivate() {
        final NumberOp numberOpInstance = new NumberOpImpl();
        final StringOp stringOpInstance = new StringOpImpl();
        final RouteSelector routeSelectorInstance = new RouteSelectorImpl();
        final SamplePolicy policyInstance = new SamplePolicy();
        final SamplePersistentPolicy persistentPolicyInstance = new SamplePersistentPolicy();

        OperationResolver resolver = new OperationResolver() {
            @Override
            public Object resolve(Class<?> contract, String qualifier) {
                if (contract == NumberOp.class) return numberOpInstance;
                if (contract == StringOp.class) return stringOpInstance;
                if (contract == RouteSelector.class) return routeSelectorInstance;
                if (contract == SamplePolicy.class) return policyInstance;
                if (contract == SamplePersistentPolicy.class) return persistentPolicyInstance;
                throw new IllegalArgumentException("Unknown: " + contract);
            }

            @Override
            public Class<?> implementationClass(Object resolved) {
                return resolved.getClass();
            }
        };

        ResumePoint<String> point = ResumePoint.named("user-approval");

        // 构造包含全部节点类型的复杂 Flow
        Flow<String, String> step1 = Flow.step(NumberOp.class, "main-num")
                .then(StringOp.class)
                .named("transform-step");

        Flow<String, String> scoped = Flow.scope("tx-scope", step1)
                .recoverWith(Flow.step((c, r) -> Outcome.accepted("fallback-val")));

        Flow<String, String> routed = Flow.route(RouteSelector.class)
                .caseOf("A", Flow.accepted("caseA"))
                .caseOf("B", Flow.accepted("caseB"))
                .otherwise(Flow.accepted("otherwiseCase"));

        Flow<String, String> fallback = Flow.firstApplicable(
                Flow.skipped(Reason.of("SKIP", "skip")),
                Flow.accepted("chosen")
        );

        Branch<String, String> branch1 = Branch.of("b1", Flow.accepted("b1-val"));
        Branch<String, String> branch2 = Branch.of("b2", Flow.accepted("b2-val"));
        Flow<String, String> parallel = Flow.parallel(branch1, branch2).join(new JoinStrategy<String>() {
            @Override
            public Outcome<String> join(ParallelResults results) {
                return Outcome.accepted("joined");
            }
        });

        Flow<String, String> controlled = parallel
                .policy(SamplePolicy.class, s -> s)
                .persistentPolicy(SamplePersistentPolicy.class, "p-qual", s -> s)
                .timeout(Duration.ofSeconds(5));

        Flow<String, Resumed<String, String>> completeFlow = scoped
                .then(routed)
                .then(fallback)
                .then(controlled)
                .await(point)
                .then(Flow.<Resumed<String, String>>identity());

        final List<String> visitedNodes = new ArrayList<String>();

        // 通过纯公开 SPI 访问者投影全部合同
        String summary = completeFlow.project(resolver, new ExecutableFlowVisitor<String>() {
            @Override
            public String visitInvoke(NodeDescriptor descriptor, ExecutableBinding binding,
                                      Function<Object, Object> project, BiFunction<Object, Object, Object> merge) {
                assertNotNull(descriptor);
                assertNotNull(binding);
                assertNotNull(project);
                assertNotNull(merge);
                visitedNodes.add("Invoke:" + descriptor.path() + ":" + binding.contractClass().getSimpleName());
                return "Invoke(" + descriptor.path() + ")";
            }

            @Override
            public String visitSequence(NodeDescriptor descriptor, List<String> children, Optional<String> scopeName) {
                assertNotNull(descriptor);
                assertNotNull(children);
                visitedNodes.add("Sequence:" + descriptor.path() + ":" + scopeName.orElse("none"));
                return "Sequence(" + String.join(",", children) + ")";
            }

            @Override
            public String visitRoute(NodeDescriptor descriptor, ExecutableBinding selectorBinding,
                                     List<ExecutableRouteCase<String>> cases, Optional<String> otherwise) {
                assertNotNull(descriptor);
                assertNotNull(selectorBinding);
                assertNotNull(cases);
                visitedNodes.add("Route:" + descriptor.path() + ":cases=" + cases.size());
                return "Route(" + cases.size() + ")";
            }

            @Override
            public String visitFallback(NodeDescriptor descriptor, FallbackTrigger trigger, List<String> branches) {
                assertNotNull(descriptor);
                assertNotNull(trigger);
                assertNotNull(branches);
                visitedNodes.add("Fallback:" + descriptor.path() + ":" + trigger);
                return "Fallback(" + trigger + ")";
            }

            @Override
            public String visitParallel(NodeDescriptor descriptor, List<ExecutableParallelBranch<String>> branches,
                                        JoinStrategy<?> join) {
                assertNotNull(descriptor);
                assertNotNull(branches);
                assertNotNull(join);
                List<Branch<?, ?>> tokens = new ArrayList<Branch<?, ?>>();
                List<Outcome<?>> outcomes = new ArrayList<Outcome<?>>();
                for (ExecutableParallelBranch<String> branch : branches) {
                    tokens.add(branch.token());
                    outcomes.add(Outcome.accepted(branch.branchPlan()));
                }
                Outcome<?> joined = join.join(ParallelResults.of(tokens, outcomes));
                assertTrue(joined instanceof Outcome.Accepted<?>);
                assertEquals("joined", ((Outcome.Accepted<?>) joined).value());
                visitedNodes.add("Parallel:" + descriptor.path() + ":branches=" + branches.size());
                return "Parallel(" + branches.size() + ")";
            }

            @Override
            public String visitAwait(NodeDescriptor descriptor, ResumePoint<?> resumePoint) {
                assertNotNull(descriptor);
                assertNotNull(resumePoint);
                assertEquals("user-approval", resumePoint.name());
                visitedNodes.add("Await:" + descriptor.path() + ":" + resumePoint.name());
                return "Await(" + resumePoint.name() + ")";
            }

            @Override
            public String visitControl(NodeDescriptor descriptor, ControlKind kind, String body,
                                       Optional<ExecutableBinding> binding, Function<Object, Object> keyProjection,
                                       Object configuration) {
                assertNotNull(descriptor);
                assertNotNull(kind);
                assertNotNull(body);
                assertNotNull(keyProjection);
                visitedNodes.add("Control:" + descriptor.path() + ":" + kind);
                return "Control(" + kind + "," + body + ")";
            }

            @Override
            public String visitComplete(NodeDescriptor descriptor, Outcome<?> outcome, boolean identity) {
                assertNotNull(descriptor);
                visitedNodes.add("Complete:" + descriptor.path() + ":id=" + identity);
                return "Complete(" + identity + ")";
            }
        });

        assertNotNull(summary);
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Invoke:")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Sequence:") && n.contains("tx-scope")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Route:")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Fallback:") && n.contains("FAILED")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Fallback:") && n.contains("SKIPPED")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Parallel:")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Await:") && n.contains("user-approval")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Control:") && n.contains("POLICY")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Control:") && n.contains("PERSISTENT_POLICY")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Control:") && n.contains("TIMEOUT")));
        assertTrue(visitedNodes.stream().anyMatch(n -> n.startsWith("Complete:")));
    }

    @Test
    public void resolverResolvesEachClassQualifierOnceAndRetainsProxy() {
        final AtomicInteger resolutions = new AtomicInteger(0);
        final NumberOp sharedProxy = new NumberOpImpl();

        OperationResolver resolver = new OperationResolver() {
            @Override
            public Object resolve(Class<?> contract, String qualifier) {
                resolutions.incrementAndGet();
                assertEquals(NumberOp.class, contract);
                assertEquals("shared-qualifier", qualifier);
                return sharedProxy;
            }

            @Override
            public Class<?> implementationClass(Object resolved) {
                return NumberOpImpl.class;
            }
        };

        // 同一流中多次引用同一个 (Class, qualifier)
        Flow<String, Integer> flow = Flow.step(NumberOp.class, "shared-qualifier")
                .use(NumberOp.class, "shared-qualifier", (Integer i) -> "num" + i, (Integer orig, Integer res) -> orig + res)
                .use(NumberOp.class, "shared-qualifier", (Integer i) -> "num" + i, (Integer orig, Integer res) -> orig + res);

        final List<Object> resolvedInstances = new ArrayList<Object>();
        flow.project(resolver, new ExecutableFlowVisitor<String>() {
            @Override
            public String visitInvoke(NodeDescriptor descriptor, ExecutableBinding binding,
                                      Function<Object, Object> project, BiFunction<Object, Object, Object> merge) {
                if (binding.contractClass() == NumberOp.class) {
                    resolvedInstances.add(binding.instance());
                }
                return "invoke:" + descriptor.path();
            }

            @Override public String visitSequence(NodeDescriptor d, List<String> c, Optional<String> s) { return "seq"; }
            @Override public String visitRoute(NodeDescriptor d, ExecutableBinding s, List<ExecutableRouteCase<String>> c, Optional<String> o) { return "route"; }
            @Override public String visitFallback(NodeDescriptor d, FallbackTrigger t, List<String> b) { return "fallback"; }
            @Override public String visitParallel(NodeDescriptor d, List<ExecutableParallelBranch<String>> b, JoinStrategy<?> j) { return "parallel"; }
            @Override public String visitAwait(NodeDescriptor d, ResumePoint<?> r) { return "await"; }
            @Override public String visitControl(NodeDescriptor d, ControlKind k, String b, Optional<ExecutableBinding> bi, Function<Object, Object> kp, Object c) { return "control"; }
            @Override public String visitComplete(NodeDescriptor d, Outcome<?> o, boolean i) { return "complete"; }
        });

        // 验证 resolve 仅调用一次，且每次投影中拿到的都是同一个实例引用
        assertEquals(1, resolutions.get());
        assertEquals(3, resolvedInstances.size());
        for (Object inst : resolvedInstances) {
            assertTrue("Instance should match the proxy instance returned by resolver", inst == sharedProxy);
        }
    }

    @Test
    public void deep5000ScopeProjectDoesNotStackOverflow() {
        Flow<Integer, Integer> flow = Flow.identity();
        for (int i = 0; i < 5000; i++) {
            flow = Flow.scope("scope-" + i, flow);
        }

        final AtomicInteger sequenceCount = new AtomicInteger(0);
        Integer result = flow.project(new ExecutableFlowVisitor<Integer>() {
            @Override public Integer visitInvoke(NodeDescriptor d, ExecutableBinding b, Function<Object, Object> p, BiFunction<Object, Object, Object> m) { return 0; }
            @Override
            public Integer visitSequence(NodeDescriptor d, List<Integer> children, Optional<String> scopeName) {
                sequenceCount.incrementAndGet();
                return children.get(0) + 1;
            }
            @Override public Integer visitRoute(NodeDescriptor d, ExecutableBinding s, List<ExecutableRouteCase<Integer>> c, Optional<Integer> o) { return 0; }
            @Override public Integer visitFallback(NodeDescriptor d, FallbackTrigger t, List<Integer> b) { return 0; }
            @Override public Integer visitParallel(NodeDescriptor d, List<ExecutableParallelBranch<Integer>> b, JoinStrategy<?> j) { return 0; }
            @Override public Integer visitAwait(NodeDescriptor d, ResumePoint<?> r) { return 0; }
            @Override public Integer visitControl(NodeDescriptor d, ControlKind k, Integer b, Optional<ExecutableBinding> bi, Function<Object, Object> kp, Object c) { return 0; }
            @Override public Integer visitComplete(NodeDescriptor d, Outcome<?> o, boolean i) { return 0; }
        });

        assertEquals(5000, sequenceCount.get());
        assertEquals(Integer.valueOf(5000), result);
    }
}
