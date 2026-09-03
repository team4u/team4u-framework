package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class StepSpecMethodsTest {

    @Test
    public void testConvenienceMethods() {
        SymbolRef op = SymbolRef.of("order.pay");
        SymbolRef project = SymbolRef.of("extractUser");
        SymbolRef merge = SymbolRef.of("mergeResult");
        SymbolRef policy1 = SymbolRef.of("rateLimit");
        SymbolRef retry1 = SymbolRef.of("simpleRetry");

        StepSpec step = new StepSpec(
                op,
                project,
                merge,
                Arrays.asList(
                        new OptionalModifierSpec(SourceSpan.UNKNOWN),
                        new TimeoutModifierSpec(Duration.ofSeconds(3), SourceSpan.UNKNOWN),
                        new NamedModifierSpec("PaymentStep", SourceSpan.UNKNOWN),
                        new PolicyModifierSpec(policy1, null, Collections.emptyMap(), SourceSpan.UNKNOWN),
                        new RetryModifierSpec(retry1, Collections.emptyMap(), SourceSpan.UNKNOWN)
                ),
                SourceSpan.UNKNOWN
        );

        Assert.assertEquals(project, step.project());
        Assert.assertEquals(merge, step.merge());
        Assert.assertTrue(step.isOptional());
        Assert.assertEquals(Duration.ofSeconds(3), step.timeout());
        Assert.assertEquals("PaymentStep", step.named());
        Assert.assertEquals(1, step.policies().size());
        Assert.assertEquals(policy1, step.policies().get(0).policy());
        Assert.assertEquals(1, step.retries().size());
        Assert.assertEquals(retry1, step.retries().get(0).retry());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testImmutabilityOfModifiers() {
        StepSpec step = new StepSpec(
                SymbolRef.of("test"),
                null,
                null,
                Arrays.asList(new OptionalModifierSpec(SourceSpan.UNKNOWN)),
                SourceSpan.UNKNOWN
        );
        step.modifiers().add(new OptionalModifierSpec(SourceSpan.UNKNOWN));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testImmutabilityOfSequenceElements() {
        SequenceSpec seq = new SequenceSpec(
                Arrays.asList(new StepSpec(SymbolRef.of("s1"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN)),
                SourceSpan.UNKNOWN
        );
        seq.elements().add(new StepSpec(SymbolRef.of("s2"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN));
    }

    @Test
    public void testConvenienceConstructors() {
        StepSpec step = new StepSpec(SymbolRef.of("s1"));
        Assert.assertEquals("s1", step.operation().id());
        Assert.assertEquals(SourceSpan.UNKNOWN, step.span());

        SequenceSpec seq = new SequenceSpec(Collections.singletonList(step));
        Assert.assertEquals(1, seq.elements().size());
        Assert.assertNull(seq.scopeName());
        Assert.assertEquals(SourceSpan.UNKNOWN, seq.span());

        FlowDefinition def = new FlowDefinition(1, "test.flow", "1.0", seq);
        Assert.assertEquals(1, def.schema());
        Assert.assertEquals("test.flow", def.id());
        Assert.assertEquals("1.0", def.version());
        Assert.assertSame(seq, def.root());
        Assert.assertNull(def.source());
        Assert.assertEquals(SourceSpan.UNKNOWN, def.span());

        FlowDefinition def2 = new FlowDefinition("test.flow2", "2.0", seq);
        Assert.assertEquals(1, def2.schema());
        Assert.assertEquals("test.flow2", def2.id());
    }
}
