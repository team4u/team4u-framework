package com.team4u.framework.flow.definition;

import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.model.ModifierSpec;
import com.team4u.framework.flow.definition.model.SourceSpan;
import com.team4u.framework.flow.definition.model.StepSpec;
import com.team4u.framework.flow.definition.model.SymbolRef;
import com.team4u.framework.flow.definition.publish.FlowPublisher;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.model.Outcome;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class FlowPublisherTest {

    @Test
    public void testPublishAndImmutability() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("op1", (OperationContext ctx, String in) -> Outcome.accepted(in + "_1"), String.class, String.class)
                .operation("op2", (OperationContext ctx, String in) -> Outcome.accepted(in + "_2"), String.class, String.class)
                .build();

        FlowPublisher publisher = new FlowPublisher(registry);

        FlowDefinition v7 = new FlowDefinition(
                1, "order.create", "7",
                new StepSpec(SymbolRef.of("op1"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                "order.flow", SourceSpan.UNKNOWN
        );

        BoundFlow boundV7 = publisher.publish(v7);
        Assert.assertNotNull(boundV7);
        Assert.assertSame(boundV7, publisher.get("order.create", "7"));
        Assert.assertSame(boundV7, publisher.get(FlowPublisher.FlowKey.of("order.create", "7")));

        // 尝试重复覆盖已发布的 (order.create, 7) 必须被拒绝
        FlowDefinition v7Duplicate = new FlowDefinition(
                1, "order.create", "7",
                new StepSpec(SymbolRef.of("op2"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                "order.flow", SourceSpan.UNKNOWN
        );

        try {
            publisher.publish(v7Duplicate);
            Assert.fail("Expected IllegalStateException for immutable published flow");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("immutable once published"));
        }

        // 发布新版本 (order.create, 8) 成功
        FlowDefinition v8 = new FlowDefinition(
                1, "order.create", "8",
                new StepSpec(SymbolRef.of("op2"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                "order.flow", SourceSpan.UNKNOWN
        );
        BoundFlow boundV8 = publisher.publish(v8);
        Assert.assertNotNull(boundV8);
        Assert.assertEquals("8", publisher.get("order.create", "8").metadata().version());

        Assert.assertEquals(2, publisher.publishedFlows().size());
        Assert.assertNull(publisher.get("non.existent"));
    }
}
