package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.compiler.FlowPaths;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.definition.model.SequenceSpec;
import com.team4u.framework.parser.SourceSpan;
import com.team4u.framework.flow.definition.model.StepSpec;
import com.team4u.framework.flow.definition.model.SymbolRef;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class SourceMapBuilderTest {

    @Test
    public void testBuildAndFindSourceSpan() {
        SourceSpan rootSpan = new SourceSpan("test.flow", 0, 1, 1, 100, 10, 1);
        SourceSpan step1Span = new SourceSpan("test.flow", 10, 2, 5, 30, 2, 20);
        SourceSpan step2Span = new SourceSpan("test.flow", 40, 3, 5, 60, 3, 20);

        StepSpec step1 = new StepSpec(SymbolRef.of("step1"), null, null, Collections.emptyList(), step1Span);
        StepSpec step2 = new StepSpec(SymbolRef.of("step2"), null, null, Collections.emptyList(), step2Span);
        SequenceSpec seqSpec = new SequenceSpec(Arrays.asList(step1, step2), rootSpan);

        Logical.Sequence seqNode = new Logical.Sequence(Arrays.asList(
                new Logical.Invoke(new Logical.Binding(null, null, "step1", Logical.BindingKind.OPERATION), null, null),
                new Logical.Invoke(new Logical.Binding(null, null, "step2", Logical.BindingKind.OPERATION), null, null)
        ), null);

        Map<String, SourceSpan> map = SourceMapBuilder.build(seqNode, seqSpec);
        Assert.assertNotNull(map);
        Assert.assertEquals(rootSpan, map.get(FlowPaths.root()));
        Assert.assertEquals(step1Span, map.get(FlowPaths.child(FlowPaths.root(), 0)));
        Assert.assertEquals(step2Span, map.get(FlowPaths.child(FlowPaths.root(), 1)));

        // Test fallback match
        SourceSpan found = SourceMapBuilder.findSourceSpan(map, FlowPaths.child(FlowPaths.root(), 0) + "/nested/unknown", SourceSpan.UNKNOWN);
        Assert.assertEquals(step1Span, found);
    }
}
