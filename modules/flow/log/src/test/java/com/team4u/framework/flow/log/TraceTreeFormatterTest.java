package com.team4u.framework.flow.log;

import org.junit.Assert;
import org.junit.Test;

public class TraceTreeFormatterTest {

    @Test
    public void testFormatSingleNode() {
        TraceNode root = new TraceNode("$", "RootFlow");
        root.setDurationMs(100);
        root.setOutcome("ACCEPTED");

        String formatted = TraceTreeFormatter.formatTree(root);
        Assert.assertTrue(formatted.contains("└── [$] RootFlow (100ms) [ACCEPTED]"));
    }

    @Test
    public void testFormatNestedTree() {
        TraceNode root = new TraceNode("$", "order-flow");
        root.setDurationMs(120);
        root.setOutcome("ACCEPTED");

        TraceNode step1 = new TraceNode("$/0", "Step 1: Validate");
        step1.setDurationMs(15);
        step1.setOutcome("ACCEPTED");

        TraceNode step2 = new TraceNode("$/1", "Step 2: Route");
        step2.setDurationMs(90);
        step2.setOutcome("ACCEPTED");
        step2.setExtra("selected=online");

        TraceNode subStep = new TraceNode("$/1/body/0", "Step 2.1: Payment");
        subStep.setDurationMs(85);
        subStep.setOutcome("ACCEPTED");
        subStep.setExtra("attempt=2");
        step2.addChild(subStep);

        root.addChild(step1);
        root.addChild(step2);

        String formatted = TraceTreeFormatter.formatTree(root);
        Assert.assertTrue(formatted.contains("└── [$] order-flow (120ms) [ACCEPTED]"));
        Assert.assertTrue(formatted.contains("├── [$/0] Step 1: Validate (15ms) [ACCEPTED]"));
        Assert.assertTrue(formatted.contains("└── [$/1] Step 2: Route (90ms) [ACCEPTED] selected=online"));
        Assert.assertTrue(formatted.contains("└── [$/1/body/0] Step 2.1: Payment (85ms) [ACCEPTED] attempt=2"));
    }

    @Test
    public void testNullRoot() {
        Assert.assertEquals("", TraceTreeFormatter.formatTree(null));
    }
}
