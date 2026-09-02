package com.team4u.framework.flow.definition.reader;

import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.model.StepSpec;
import com.team4u.framework.flow.definition.model.SymbolRef;
import com.team4u.framework.parser.SourceSpan;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FlowDefinitionReaderTest {

    @Test
    public void testReaderBasicRead() {
        FlowDefinition def1 = new FlowDefinition(1, "flow1", "1", new StepSpec(SymbolRef.of("op1"), SourceSpan.UNKNOWN), "test.src", SourceSpan.UNKNOWN);
        FlowDefinition def2 = new FlowDefinition(1, "flow2", "1", new StepSpec(SymbolRef.of("op2"), SourceSpan.UNKNOWN), "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Arrays.asList(def1, def2);

        List<FlowDefinition> list = reader.read("content", "my.flow");
        Assert.assertEquals(2, list.size());
        Assert.assertEquals("flow1", list.get(0).id());
        Assert.assertEquals("flow2", list.get(1).id());
    }

    @Test
    public void testEmptyReaderOutput() {
        FlowDefinitionReader emptyReader = (source, sourceName) -> Collections.emptyList();
        List<FlowDefinition> list = emptyReader.read("dummy", null);
        Assert.assertTrue(list.isEmpty());
    }
}
