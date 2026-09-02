package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.model.SequenceSpec;
import com.team4u.framework.flow.definition.model.SourceSpan;
import com.team4u.framework.flow.definition.model.StepSpec;
import com.team4u.framework.flow.definition.model.SymbolRef;
import com.team4u.framework.flow.definition.reader.FlowDefinitionReader;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.dsl.reader.TextFlowDefinitionReader;
import com.team4u.framework.flow.model.Outcome;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class FlowDslEngineTest {

    public static class UpperOperation implements Operation<String, String> {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input.toUpperCase());
        }
    }

    @Test
    public void testDefaultEngineBasicParseAndBind() {
        String dsl = "flow sample.flow {\n" +
                "    step op.upper\n" +
                "}";

        FlowDslEngine engine = FlowDslEngine.defaultEngine();
        Assert.assertNotNull(engine.reader());
        Assert.assertTrue(engine.reader() instanceof TextFlowDefinitionReader);

        FlowDefinition def = engine.parse(dsl, "sample.flow");
        Assert.assertEquals("sample.flow", def.id());
        Assert.assertTrue(def.root() instanceof StepSpec);

        List<FlowDefinition> allDefs = engine.parseAll(dsl);
        Assert.assertEquals(1, allDefs.size());

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("op.upper", (OperationContext ctx, String in) -> Outcome.accepted(in.toUpperCase()), String.class, String.class)
                .build();

        BoundFlow bound = engine.bind(dsl, registry);
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        Assert.assertEquals("HELLO", exec.run("hello").requireAccepted());
    }

    @Test
    public void testEngineBuilderWithPresetRegistryAndResolver() {
        String dsl = "flow test.flow {\n" +
                "    step bean.upper\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("bean.upper", UpperOperation.class)
                .build();

        FlowDslEngine engine = FlowDslEngine.builder()
                .registry(registry)
                .resolver((contract, qualifier) -> new UpperOperation())
                .build();

        Assert.assertEquals(registry, engine.registry());
        Assert.assertNotNull(engine.resolver());

        // 使用引擎预设的 registry 与 resolver 进行无参 bind(dsl)
        BoundFlow bound = engine.bind(dsl);
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        Assert.assertEquals("WORLD", exec.run("world").requireAccepted());
    }

    @Test
    public void testEngineWithCustomReader() {
        // 自定义读取器：例如模拟从某种自定义格式生成 FlowDefinition
        FlowDefinitionReader customReader = (source, sourceName) -> {
            StepSpec step = new StepSpec(SymbolRef.of("custom.op"), SourceSpan.UNKNOWN);
            FlowDefinition customDef = new FlowDefinition(
                    1,
                    source.trim(),
                    "1",
                    step,
                    sourceName,
                    SourceSpan.UNKNOWN);
            return Collections.singletonList(customDef);
        };

        FlowDslEngine engine = FlowDsl.withReader(customReader);
        Assert.assertSame(customReader, engine.reader());

        FlowDefinition parsed = engine.parse("custom.flow.id", "custom_file.src");
        Assert.assertEquals("custom.flow.id", parsed.id());
        Assert.assertEquals("custom_file.src", parsed.source());

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("custom.op", (OperationContext ctx, String in) -> Outcome.accepted("CUSTOM:" + in), String.class, String.class)
                .build();

        BoundFlow bound = engine.bind("custom.flow.id", registry);
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        Assert.assertEquals("CUSTOM:test", exec.run("test").requireAccepted());
    }

    @Test
    public void testEngineMultiFlowAndBindTarget() {
        String dsl = "flow sub1 {\n" +
                "    step op.one\n" +
                "}\n" +
                "flow sub2 {\n" +
                "    step op.two\n" +
                "}";

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("op.one", (OperationContext ctx, String in) -> Outcome.accepted("1:" + in), String.class, String.class)
                .operation("op.two", (OperationContext ctx, String in) -> Outcome.accepted("2:" + in), String.class, String.class)
                .build();

        FlowDslEngine engine = FlowDslEngine.builder().registry(registry).build();

        BoundFlow bound1 = engine.bindTarget(dsl, "sub1");
        LocalExecutable<String, String> exec1 = bound1.compileLocal(String.class, String.class);
        Assert.assertEquals("1:abc", exec1.run("abc").requireAccepted());

        BoundFlow bound2 = engine.bindTarget(dsl, "sub2");
        LocalExecutable<String, String> exec2 = bound2.compileLocal(String.class, String.class);
        Assert.assertEquals("2:abc", exec2.run("abc").requireAccepted());
    }

    @Test
    public void testUnknownTargetFlowThrowsDiagnostic() {
        String dsl = "flow sub1 { step op.one }";
        FlowDslEngine engine = FlowDslEngine.defaultEngine();

        try {
            engine.bindTarget(dsl, "non_existent_flow");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertTrue(ex.getMessage().contains("UNKNOWN_FLOW"));
        }
    }

    @Test
    public void testEmptyReaderOutputThrowsDiagnostic() {
        FlowDefinitionReader emptyReader = (source, sourceName) -> Collections.emptyList();
        FlowDslEngine engine = FlowDslEngine.withReader(emptyReader);

        try {
            engine.parse("dummy");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertTrue(ex.getMessage().contains("INVALID_DEFINITION"));
        }

        try {
            engine.bind("dummy");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertTrue(ex.getMessage().contains("INVALID_DEFINITION"));
        }
    }

    @Test
    public void testFlowDslFacadeStaticHelpers() {
        Assert.assertSame(FlowDslEngine.defaultEngine(), FlowDsl.engine());
        Assert.assertNotNull(FlowDsl.builder());

        FlowDefinitionReader noopReader = (source, sourceName) -> Collections.emptyList();
        FlowDslEngine customEngine = FlowDsl.withReader(noopReader);
        Assert.assertSame(noopReader, customEngine.reader());
    }
}
