package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowExecution;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.Flows;
import com.team4u.framework.flow.RunOptions;
import com.team4u.framework.flow.StopReason;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Flow 测试断言与桩对象功能测试。
 *
 * @author jay.wu
 */
public class FlowAssertionsTest {

    @Test
    public void flowResultAssert_succeeded() {
        FlowResult<String> result = FlowResult.succeeded("ok");
        FlowAssertions.assertThat(result)
                .isSucceeded()
                .hasValue("ok");
    }

    @Test
    public void flowResultAssert_stopped() {
        StopReason reason = StopReason.of("STOP_CODE", "Stop message");
        FlowResult<String> result = FlowResult.stopped(reason);
        FlowAssertions.assertThat(result)
                .isStopped()
                .hasStopCode("STOP_CODE")
                .hasStopReason(reason);
    }

    @Test
    public void flowResultAssert_failed() {
        IllegalArgumentException cause = new IllegalArgumentException("Bad input");
        FlowResult<String> result = FlowResult.failed("node-fail", "path/to/fail", cause);
        FlowAssertions.assertThat(result)
                .isFailed()
                .hasFailedNodeId("node-fail")
                .hasFailedNodePath("path/to/fail")
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasCauseMessage("Bad input");
    }

    @Test
    public void flowExecutionAndTraceAssert() {
        StepStub<Integer, Integer> stub = StepStub.function(in -> in + 10);
        ActionStub<Integer> actionStub = ActionStub.create();

        Flow<Integer, Integer> flow = Flows.<Integer>begin("test-assert-flow")
                .step("step-stub", stub)
                .tap("action-stub", actionStub)
                .build();

        FlowExecution<Integer> execution = flow.run(5, RunOptions.builder()
                .executionId("exec-001")
                .trace(true)
                .build());

        FlowAssertions.assertThat(execution)
                .hasFlowId("test-assert-flow")
                .hasExecutionId("exec-001");

        FlowAssertions.assertThat(execution.result())
                .isSucceeded()
                .hasValue(15);

        FlowAssertions.assertThat(execution.trace())
                .hasExecutedNode("step-stub")
                .hasExecutedNode("action-stub")
                .hasExecutionOrder("step-stub", "action-stub")
                .hasNodeStatus("step-stub", FlowResult.Kind.SUCCEEDED)
                .hasNodeCount(2);

        Assert.assertEquals(1, stub.invocationCount());
        Assert.assertEquals(Integer.valueOf(5), stub.lastInput());
        Assert.assertEquals(1, actionStub.invocationCount());
        Assert.assertEquals(Integer.valueOf(15), actionStub.lastInput());
    }

    @Test
    public void conditionStub() throws Exception {
        ConditionStub<String> stub = ConditionStub.always(true);
        Assert.assertTrue(stub.test("abc"));
        Assert.assertEquals(1, stub.invocationCount());
        Assert.assertEquals(Arrays.asList("abc"), stub.recordedInputs());
    }
}
