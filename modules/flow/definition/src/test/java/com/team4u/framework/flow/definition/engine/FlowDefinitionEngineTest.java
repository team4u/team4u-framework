package com.team4u.framework.flow.definition.engine;

import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.model.StepSpec;
import com.team4u.framework.flow.definition.model.SymbolRef;
import com.team4u.framework.flow.definition.reader.FlowDefinitionReader;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.parser.SourceSpan;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FlowDefinitionEngineTest {

    public static class UpperOperation implements Operation<String, String> {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input.toUpperCase());
        }
    }

    @Test
    public void testSingleFlowBinding() {
        FlowDefinition def = new FlowDefinition(
                1, "sample.flow", "1",
                new StepSpec(SymbolRef.of("op.upper"), SourceSpan.UNKNOWN),
                "sample.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Collections.singletonList(def);
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("op.upper", (OperationContext ctx, String in) -> Outcome.accepted(in.toUpperCase()), String.class, String.class)
                .build();

        FlowDefinitionEngine engine = FlowDefinitionEngine.builder()
                .reader(reader)
                .registry(registry)
                .build();

        Assert.assertSame(reader, engine.reader());
        Assert.assertSame(registry, engine.registry());

        List<FlowDefinition> defs = engine.readAll("dummy", "sample.src");
        Assert.assertEquals(1, defs.size());

        BoundFlow bound = engine.bind("dummy");
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        Assert.assertEquals("HELLO", exec.run("hello").requireAccepted());
    }

    @Test
    public void testEngineBuilderWithPresetRegistryAndResolver() {
        FlowDefinition def = new FlowDefinition(
                1, "test.flow", "1",
                new StepSpec(SymbolRef.of("bean.upper"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Collections.singletonList(def);
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("bean.upper", UpperOperation.class)
                .build();

        FlowDefinitionEngine engine = FlowDefinitionEngine.builder()
                .reader(reader)
                .registry(registry)
                .resolver((contract, qualifier) -> new UpperOperation())
                .build();

        BoundFlow bound = engine.bind("dummy");
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        Assert.assertEquals("WORLD", exec.run("world").requireAccepted());
    }

    @Test
    public void testMultipleFlowsWithExplicitTarget() {
        FlowDefinition sub1 = new FlowDefinition(
                1, "sub1", "1",
                new StepSpec(SymbolRef.of("op.one"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);
        FlowDefinition sub2 = new FlowDefinition(
                1, "sub2", "1",
                new StepSpec(SymbolRef.of("op.two"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Arrays.asList(sub1, sub2);
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("op.one", (OperationContext ctx, String in) -> Outcome.accepted("1:" + in), String.class, String.class)
                .operation("op.two", (OperationContext ctx, String in) -> Outcome.accepted("2:" + in), String.class, String.class)
                .build();

        FlowDefinitionEngine engine = FlowDefinitionEngine.builder()
                .reader(reader)
                .registry(registry)
                .build();

        BoundFlow bound1 = engine.bindTarget("dummy", "sub1");
        LocalExecutable<String, String> exec1 = bound1.compileLocal(String.class, String.class);
        Assert.assertEquals("1:abc", exec1.run("abc").requireAccepted());

        BoundFlow bound2 = engine.bindTarget("dummy", "sub2");
        LocalExecutable<String, String> exec2 = bound2.compileLocal(String.class, String.class);
        Assert.assertEquals("2:abc", exec2.run("abc").requireAccepted());
    }

    @Test
    public void testMultipleFlowsWithoutTargetThrowsAmbiguous() {
        FlowDefinition sub1 = new FlowDefinition(
                1, "sub1", "1",
                new StepSpec(SymbolRef.of("op.one"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);
        FlowDefinition sub2 = new FlowDefinition(
                1, "sub2", "1",
                new StepSpec(SymbolRef.of("op.two"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Arrays.asList(sub1, sub2);
        FlowDefinitionEngine engine = FlowDefinitionEngine.withReader(reader);

        try {
            engine.bind("dummy");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.AMBIGUOUS_TARGET_FLOW, ex.diagnostic().code());
            Assert.assertTrue(ex.getMessage().contains("Multiple flow definitions found"));
        }
    }

    @Test
    public void testEmptyReaderThrowsInvalidDefinition() {
        FlowDefinitionReader reader = (source, sourceName) -> Collections.emptyList();
        FlowDefinitionEngine engine = FlowDefinitionEngine.withReader(reader);

        try {
            engine.bind("dummy");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.INVALID_DEFINITION, ex.diagnostic().code());
        }
    }

    @Test
    public void testDuplicateFlowIdInBatchThrows() {
        FlowDefinition def1 = new FlowDefinition(
                1, "dup.id", "1",
                new StepSpec(SymbolRef.of("op.one"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);
        FlowDefinition def2 = new FlowDefinition(
                1, "dup.id", "1",
                new StepSpec(SymbolRef.of("op.two"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Arrays.asList(def1, def2);
        FlowDefinitionEngine engine = FlowDefinitionEngine.withReader(reader);

        try {
            engine.bindTarget("dummy", "dup.id");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.DUPLICATE_FLOW_ID, ex.diagnostic().code());
        }
    }

    @Test
    public void testRegistryCollisionThrowsDuplicateFlowId() {
        FlowDefinition subRegistered = new FlowDefinition(
                1, "registered.flow", "1",
                new StepSpec(SymbolRef.of("op.one"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .subflow(subRegistered)
                .build();

        FlowDefinition newDef = new FlowDefinition(
                1, "registered.flow", "1",
                new StepSpec(SymbolRef.of("op.two"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Collections.singletonList(newDef);
        FlowDefinitionEngine engine = FlowDefinitionEngine.builder()
                .reader(reader)
                .registry(registry)
                .build();

        try {
            engine.bind("dummy");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.DUPLICATE_FLOW_ID, ex.diagnostic().code());
        }
    }

    @Test
    public void testUnknownTargetFlowThrows() {
        FlowDefinition def = new FlowDefinition(
                1, "flow1", "1",
                new StepSpec(SymbolRef.of("op1"), SourceSpan.UNKNOWN),
                "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Collections.singletonList(def);
        FlowDefinitionEngine engine = FlowDefinitionEngine.withReader(reader);

        try {
            engine.bindTarget("dummy", "non_existent");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.UNKNOWN_FLOW, ex.diagnostic().code());
        }
    }

    @Test(expected = NullPointerException.class)
    public void testNullReaderInEngineThrows() {
        new FlowDefinitionEngine(null, null, null);
    }

    @Test(expected = NullPointerException.class)
    public void testReaderReturnsNullThrows() {
        FlowDefinitionReader nullReader = (source, sourceName) -> null;
        FlowDefinitionEngine engine = FlowDefinitionEngine.withReader(nullReader);
        engine.readAll("dummy");
    }
}
