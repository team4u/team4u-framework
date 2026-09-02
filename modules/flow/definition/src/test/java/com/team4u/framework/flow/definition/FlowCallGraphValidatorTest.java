package com.team4u.framework.flow.definition;

import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.definition.type.TypeCheckResult;
import com.team4u.framework.flow.definition.type.TypeChecker;
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
        CallSpec callSelf = new CallSpec(SymbolRef.of("flowA"), new SourceSpan("test.src", 2, 5, 10, 20, 2, 15));
        FlowDefinition flowA = new FlowDefinition(
                1, "flowA", "1",
                callSelf,
                "test.src",
                new SourceSpan("test.src", 1, 1, 0, 30, 3, 1));

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
        CallSpec callB = new CallSpec(SymbolRef.of("flowB"), new SourceSpan("test.src", 2, 5, 10, 20, 2, 15));
        FlowDefinition flowA = new FlowDefinition(
                1, "flowA", "1",
                callB,
                "test.src",
                SourceSpan.UNKNOWN);

        CallSpec callA = new CallSpec(SymbolRef.of("flowA"), new SourceSpan("test.src", 6, 5, 40, 50, 6, 15));
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

        CallSpec callBackToA = new CallSpec(SymbolRef.of("flowA"), new SourceSpan("test.src", 15, 5, 100, 110, 15, 15));
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
}
