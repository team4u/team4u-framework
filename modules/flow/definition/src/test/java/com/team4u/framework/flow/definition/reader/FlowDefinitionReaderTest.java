package com.team4u.framework.flow.definition.reader;

import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.model.SourceSpan;
import com.team4u.framework.flow.definition.model.StepSpec;
import com.team4u.framework.flow.definition.model.SymbolRef;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FlowDefinitionReaderTest {

    @Test
    public void testReaderDefaultMethods() {
        FlowDefinition def1 = new FlowDefinition(1, "flow1", "1", new StepSpec(SymbolRef.of("op1"), SourceSpan.UNKNOWN), "test.src", SourceSpan.UNKNOWN);
        FlowDefinition def2 = new FlowDefinition(1, "flow2", "1", new StepSpec(SymbolRef.of("op2"), SourceSpan.UNKNOWN), "test.src", SourceSpan.UNKNOWN);

        FlowDefinitionReader reader = (source, sourceName) -> Arrays.asList(def1, def2);

        // read(source)
        List<FlowDefinition> list = reader.read("content");
        Assert.assertEquals(2, list.size());

        // readDefinition(source, sourceName) 返回最后一个
        FlowDefinition lastWithSource = reader.readDefinition("content", "my.flow");
        Assert.assertEquals("flow2", lastWithSource.id());

        // readDefinition(source)
        FlowDefinition last = reader.readDefinition("content");
        Assert.assertEquals("flow2", last.id());
    }

    @Test(expected = FlowDiagnosticException.class)
    public void testReadDefinitionThrowsWhenEmpty() {
        FlowDefinitionReader emptyReader = (source, sourceName) -> Collections.emptyList();
        emptyReader.readDefinition("dummy");
    }

    @Test(expected = FlowDiagnosticException.class)
    public void testReadDefinitionThrowsWhenNull() {
        FlowDefinitionReader nullReader = (source, sourceName) -> null;
        nullReader.readDefinition("dummy");
    }
}
