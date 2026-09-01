package com.team4u.framework.flow.definition;

import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.*;

public class FlowBinderTest {

    static class Order {
        String id;
        int count;

        Order(String id, int count) {
            this.id = id;
            this.count = count;
        }
    }

    @Test
    public void testBindAndExecuteStep() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", (OperationContext ctx, Order in) -> Outcome.accepted(new Order(in.id, in.count + 1)), Order.class, Order.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.simple", "1",
                new StepSpec(SymbolRef.of("order.validate"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                "order.flow", SourceSpan.UNKNOWN
        );

        BoundFlow bound = FlowBinder.bind(def, registry);
        Assert.assertNotNull(bound.flow());
        LocalExecutable<Order, Order> exec = bound.compileLocal();
        FlowResult<Order> result = exec.run(new Order("1001", 1));
        Assert.assertEquals(2, result.requireAccepted().count);
    }

    @Test
    public void testBindWithModifiersOrder() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.step", (OperationContext ctx, String in) -> Outcome.accepted(in + "_step"), String.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.mod", "1",
                new StepSpec(
                        SymbolRef.of("order.step"),
                        null,
                        null,
                        Arrays.<ModifierSpec>asList(
                                new TimeoutModifierSpec(Duration.ofSeconds(1), SourceSpan.UNKNOWN),
                                new NamedModifierSpec("MyStep", SourceSpan.UNKNOWN)
                        ),
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        BoundFlow bound = FlowBinder.bind(def, registry);
        LocalExecutable<String, String> exec = bound.compileLocal();
        FlowResult<String> result = exec.run("init");
        Assert.assertEquals("init_step", result.requireAccepted());
    }

    @Test
    public void testBindParallelAndJoin() {
        JoinStrategy<String> joinStrategy = results -> Outcome.accepted("JOINED:" + results.branches().size());

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("branch.a", (OperationContext ctx, String in) -> Outcome.accepted("A:" + in), String.class, String.class)
                .operation("branch.b", (OperationContext ctx, String in) -> Outcome.accepted("B:" + in), String.class, String.class)
                .join("test.join", joinStrategy, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "parallel.flow", "1",
                new ParallelSpec(
                        Arrays.asList(
                                new BranchSpec("branchA", new StepSpec(SymbolRef.of("branch.a"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN),
                                new BranchSpec("branchB", new StepSpec(SymbolRef.of("branch.b"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        SymbolRef.of("test.join"),
                        SourceSpan.UNKNOWN
                ),
                "parallel.flow", SourceSpan.UNKNOWN
        );

        BoundFlow bound = FlowBinder.bind(def, registry);
        LocalExecutable<String, String> exec = bound.compileLocal();
        FlowResult<String> result = exec.run("input");
        Assert.assertEquals("JOINED:2", result.requireAccepted());
    }

    @Test
    public void testBindAwaitResumePoint() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .resumePoint("user.confirm", String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "await.flow", "1",
                new AwaitSpec(SymbolRef.of("user.confirm"), SourceSpan.UNKNOWN),
                "await.flow", SourceSpan.UNKNOWN
        );

        BoundFlow bound = FlowBinder.bind(def, registry);
        Assert.assertNotNull(bound.flow());
        LocalExecutable<String, Resumed<String, String>> exec = bound.compileLocal();
        FlowResult<Resumed<String, String>> result = exec.run("current_val");
        Assert.assertTrue(result instanceof FlowResult.Suspended);
        Assert.assertTrue(((FlowResult.Suspended<Resumed<String, String>>) result).awaiting(ResumePoint.named("user.confirm")));
    }

    @Test
    public void testCompilerErrorMapsToSourceSpan() {
        // Parallel block with duplicate branch name triggers DUPLICATE_BRANCH in Compiler
        JoinStrategy<String> joinStrategy = results -> Outcome.accepted("JOINED");
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("branch.a", (OperationContext ctx, String in) -> Outcome.accepted("A"), String.class, String.class)
                .join("test.join", joinStrategy, String.class)
                .build();

        SourceSpan branch1Span = new SourceSpan("order.flow", 10, 5, 12, 5);
        SourceSpan branch2Span = new SourceSpan("order.flow", 14, 5, 16, 5);

        FlowDefinition def = new FlowDefinition(
                1, "dup.branch.flow", "1",
                new ParallelSpec(
                        Arrays.asList(
                                new BranchSpec("sameName", new StepSpec(SymbolRef.of("branch.a"), null, null, Collections.<ModifierSpec>emptyList(), branch1Span), branch1Span),
                                new BranchSpec("sameName", new StepSpec(SymbolRef.of("branch.a"), null, null, Collections.<ModifierSpec>emptyList(), branch2Span), branch2Span)
                        ),
                        SymbolRef.of("test.join"),
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        try {
            FlowBinder.bind(def, registry);
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertFalse(ex.getDiagnostics().isEmpty());
            Assert.assertEquals("DUPLICATE_BRANCH", ex.getDiagnostics().get(0).code());
        }
    }
}
