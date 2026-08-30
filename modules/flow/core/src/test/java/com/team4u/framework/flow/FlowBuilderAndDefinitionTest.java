package com.team4u.framework.flow;

import org.junit.Assert;
import org.junit.Test;

/**
 * 流程定义与 Builder 校验规则测试。
 *
 * @author jay.wu
 */
public class FlowBuilderAndDefinitionTest {

    @Test
    public void nullInput_rejectedAtRuntime() {
        Flow<String, String> flow = Flows.<String>begin("flow1")
                .step("s1", in -> in + "-ok")
                .build();

        try {
            flow.call(null);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("null"));
        }

        try {
            flow.run(null);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("null"));
        }
    }

    @Test
    public void nullStepOutput_rejectedAtRuntime() {
        Flow<String, String> flow = Flows.<String>begin("flow2")
                .step("null-step", in -> (String) null)
                .build();

        FlowExecution<String> exec = flow.run("hello");
        Assert.assertTrue(exec.result().isFailed());
        Assert.assertEquals("null-step", exec.result().failure().nodeId());
        Assert.assertTrue(exec.result().failure().cause() instanceof IllegalStateException);
    }

    @Test
    public void duplicateNodeId_rejectedOnBuild() {
        try {
            Flows.<String>begin("flow3")
                    .step("same-id", in -> in + "-1")
                    .step("same-id", in -> in + "-2")
                    .build();
            Assert.fail("Expected IllegalArgumentException for duplicate node ID");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("same-id"));
        }
    }

    @Test
    public void emptySequence_rejectedOnBuild() {
        try {
            Flows.<String>begin("empty-flow").build();
            Assert.fail("Expected IllegalStateException for empty flow");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("empty") || e.getMessage().contains("contain at least one node"));
        }
    }

    @Test
    public void duplicateBranchKey_rejectedOnBuild() {
        try {
            Flows.<String>begin("choose-dup")
                    .choose("ch1", in -> in)
                    .when("A", Flows.step("step-a1", in -> in))
                    .when("A", Flows.step("step-a2", in -> in))
                    .end()
                    .build();
            Assert.fail("Expected IllegalArgumentException for duplicate branch key");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Duplicate branch key"));
        }
    }

    @Test
    public void builderImmutability_subsequentCallsDoNotChangeBuiltFlow() {
        FlowBuilder<String, String> builder = Flows.<String>begin("immu-flow")
                .step("step1", in -> in + "-1");

        Flow<String, String> flow1 = builder.build();

        // Continue adding step2 to builder
        Flow<String, String> flow2 = builder.step("step2", in -> in + "-2").build();

        Assert.assertEquals("val-1", flow1.call("val"));
        Assert.assertEquals("val-1-2", flow2.call("val"));
    }

    @Test
    public void stepInstance_reusedWithDifferentNodeIds() {
        Step<Integer, Integer> incrementer = in -> in + 1;

        Flow<Integer, Integer> flow = Flows.<Integer>begin("reuse-step")
                .step("inc-1", incrementer)
                .step("inc-2", incrementer)
                .step("inc-3", incrementer)
                .build();

        Integer res = flow.call(0);
        Assert.assertEquals(Integer.valueOf(3), res);
    }
}
