package com.team4u.framework.flow.definition;

import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.definition.type.*;
import com.team4u.framework.flow.definition.validation.FlowCallGraphValidator;
import com.team4u.framework.parser.SourceSpan;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FlowCallGraphValidatorTest {

    @Test
    public void testDirectSelfLoopDetected() {
        // flow A: call A
        CallSpec callSelf = new CallSpec(SymbolRef.of("flowA"), new SourceSpan("test.src", 2, 1, 3, 20, 2, 15));
        FlowDefinition flowA = new FlowDefinition(
                1, "flowA", "1",
                callSelf,
                "test.src",
                new SourceSpan("test.src", 1, 1, 1, 30, 3, 1));

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .subflow(flowA)
                .build();

        List<Diagnostic> diagnostics = FlowCallGraphValidator.validate(flowA, registry);
        Assert.assertEquals(1, diagnostics.size());
        Assert.assertEquals(DiagnosticCodes.CYCLIC_FLOW_CALL, diagnostics.get(0).code());
        Assert.assertEquals(callSelf.span(), diagnostics.get(0).span());
        Assert.assertTrue(diagnostics.get(0).message().contains("flowA"));

        // TypeChecker integration
        TypeCheckResult result = TypeChecker.check(flowA, registry);
        Assert.assertFalse(result.success());
        Assert.assertTrue(result.diagnostics().stream().anyMatch(d -> DiagnosticCodes.CYCLIC_FLOW_CALL.equals(d.code())));

        // FlowBinder integration
        try {
            FlowBinder.bind(flowA, registry);
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertTrue(ex.diagnostics().stream().anyMatch(d -> DiagnosticCodes.CYCLIC_FLOW_CALL.equals(d.code())));
        }
    }

    @Test
    public void testDirectCycleBetweenTwoFlowsDetected() {
        // flow A: call B
        // flow B: call A
        CallSpec callB = new CallSpec(SymbolRef.of("flowB"), new SourceSpan("test.src", 2, 1, 3, 20, 2, 15));
        FlowDefinition flowA = new FlowDefinition(
                1, "flowA", "1",
                callB,
                "test.src",
                SourceSpan.UNKNOWN);

        CallSpec callA = new CallSpec(SymbolRef.of("flowA"), new SourceSpan("test.src", 6, 2, 5, 50, 6, 15));
        FlowDefinition flowB = new FlowDefinition(
                1, "flowB", "1",
                callA,
                "test.src",
                SourceSpan.UNKNOWN);

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .subflow(flowA)
                .subflow(flowB)
                .build();

        List<Diagnostic> diagnostics = FlowCallGraphValidator.validate(flowA, registry);
        Assert.assertEquals(1, diagnostics.size());
        Assert.assertEquals(DiagnosticCodes.CYCLIC_FLOW_CALL, diagnostics.get(0).code());
        Assert.assertEquals(callA.span(), diagnostics.get(0).span());

        TypeCheckResult result = TypeChecker.check(flowA, registry);
        Assert.assertFalse(result.success());
        Assert.assertTrue(result.diagnostics().stream().anyMatch(d -> DiagnosticCodes.CYCLIC_FLOW_CALL.equals(d.code())));
    }

    @Test
    public void testIndirectCycleThroughSequenceAndRoute() {
        // flow A: sequence [ step op1, call B ]
        // flow B: route op.sel { case 1 -> call C, otherwise -> call B }
        // flow C: call A
        CallSpec callB = new CallSpec(SymbolRef.of("flowB"), SourceSpan.UNKNOWN);
        SequenceSpec seqA = new SequenceSpec(Arrays.asList(
                new StepSpec(SymbolRef.of("op1"), SourceSpan.UNKNOWN),
                callB
        ), SourceSpan.UNKNOWN);
        FlowDefinition flowA = new FlowDefinition(1, "flowA", "1", seqA, "test.src", SourceSpan.UNKNOWN);

        CallSpec callC = new CallSpec(SymbolRef.of("flowC"), SourceSpan.UNKNOWN);
        RouteSpec routeB = new RouteSpec(
                SymbolRef.of("op.sel"),
                Collections.singletonList(new CaseSpec("1", callC, SourceSpan.UNKNOWN)),
                null,
                SourceSpan.UNKNOWN
        );
        FlowDefinition flowB = new FlowDefinition(1, "flowB", "1", routeB, "test.src", SourceSpan.UNKNOWN);

        CallSpec callBackToA = new CallSpec(SymbolRef.of("flowA"), new SourceSpan("test.src", 15, 2, 5, 110, 15, 15));
        FlowDefinition flowC = new FlowDefinition(1, "flowC", "1", callBackToA, "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .subflow(flowA)
                .subflow(flowB)
                .subflow(flowC)
                .operation("op1", (ctx, in) -> Outcome.accepted(in), String.class, String.class)
                .operation("op.sel", (ctx, in) -> Outcome.accepted(1), String.class, Integer.class)
                .build();

        List<Diagnostic> diagnostics = FlowCallGraphValidator.validate(flowA, registry);
        Assert.assertEquals(1, diagnostics.size());
        Assert.assertEquals(DiagnosticCodes.CYCLIC_FLOW_CALL, diagnostics.get(0).code());
        Assert.assertEquals(callBackToA.span(), diagnostics.get(0).span());
    }

    @Test
    public void testAcyclicGraphWithSharedSubflowSucceeds() {
        // Diamond call graph:
        // A -> B, A -> C, B -> D, C -> D (DAG, no cycle)
        FlowDefinition flowD = new FlowDefinition(
                1, "flowD", "1",
                new StepSpec(SymbolRef.of("op1"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinition flowB = new FlowDefinition(
                1, "flowB", "1",
                new CallSpec(SymbolRef.of("flowD"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinition flowC = new FlowDefinition(
                1, "flowC", "1",
                new CallSpec(SymbolRef.of("flowD"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinition flowA = new FlowDefinition(
                1, "flowA", "1",
                new SequenceSpec(Arrays.asList(
                        new CallSpec(SymbolRef.of("flowB"), SourceSpan.UNKNOWN),
                        new CallSpec(SymbolRef.of("flowC"), SourceSpan.UNKNOWN)
                ), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .subflow(flowA)
                .subflow(flowB)
                .subflow(flowC)
                .subflow(flowD)
                .operation("op1", (ctx, in) -> Outcome.accepted(in), String.class, String.class)
                .build();

        List<Diagnostic> diagnostics = FlowCallGraphValidator.validate(flowA, registry);
        Assert.assertTrue(diagnostics.isEmpty());

        TypeCheckResult result = TypeChecker.check(flowA, registry);
        Assert.assertTrue(result.success());
    }

    static class CustomCompositeSpec implements FlowSpec {
        private final FlowSpec child;
        private final SourceSpan span;

        CustomCompositeSpec(FlowSpec child, SourceSpan span) {
            this.child = child;
            this.span = span != null ? span : SourceSpan.UNKNOWN;
        }

        public FlowSpec child() {
            return child;
        }

        @Override
        public SourceSpan span() {
            return span;
        }
    }

    static class CustomCompositeSpecTypeChecker implements SpecTypeChecker<CustomCompositeSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return CustomCompositeSpec.class;
        }

        @Override
        public TypeRef check(CustomCompositeSpec spec, TypeRef currentType, TypeCheckContext context) {
            return context.checkSpec(spec.child(), currentType);
        }
    }

    @Test
    public void testCustomFlowSpecCycleDetectedByCallSpecTypeCheckerFallback() {
        // flow A: CustomCompositeSpec -> call A
        CallSpec callSelf = new CallSpec(SymbolRef.of("flowA"), new SourceSpan("test.src", 10, 2, 5, 30, 2, 20));
        CustomCompositeSpec customSpec = new CustomCompositeSpec(callSelf, SourceSpan.UNKNOWN);
        FlowDefinition flowA = new FlowDefinition(
                1, "flowA", "1",
                customSpec,
                "test.src",
                SourceSpan.UNKNOWN);

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .subflow(flowA)
                .build();

        // FlowCallGraphValidator doesn't know about CustomCompositeSpec
        List<Diagnostic> validatorDiagnostics = FlowCallGraphValidator.validate(flowA, registry);
        Assert.assertTrue(validatorDiagnostics.isEmpty());

        // But TypeChecker with CustomCompositeSpecTypeChecker discovers the cycle via CallSpecTypeChecker defensive check!
        SpecTypeCheckerRegistry checkerRegistry = new SpecTypeCheckerRegistry();
        checkerRegistry.register(new SpecTypeCheckers.StepSpecTypeChecker());
        checkerRegistry.register(new SpecTypeCheckers.CallSpecTypeChecker());
        checkerRegistry.register(new SpecTypeCheckers.SequenceSpecTypeChecker());
        checkerRegistry.register(new CustomCompositeSpecTypeChecker());
        checkerRegistry.freeze();

        TypeChecker typeChecker = new TypeChecker(registry, checkerRegistry);
        TypeCheckResult result = typeChecker.check(flowA);
        Assert.assertFalse(result.success());
        Assert.assertTrue(result.diagnostics().stream().anyMatch(d ->
                DiagnosticCodes.CYCLIC_FLOW_CALL.equals(d.code()) && d.message().contains("flowA")));
    }
}
