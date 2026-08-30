package com.team4u.framework.flow.test;

import com.team4u.framework.flow.FlowExecution;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.FlowTrace;

/**
 * Flow 测试断言入口。
 *
 * @author jay.wu
 */
public final class FlowAssertions {

    private FlowAssertions() {
    }

    public static <O> FlowResultAssert<O> assertThat(FlowResult<O> result) {
        return new FlowResultAssert<>(result);
    }

    public static <O> FlowExecutionAssert<O> assertThat(FlowExecution<O> execution) {
        return new FlowExecutionAssert<>(execution);
    }

    public static FlowTraceAssert assertThat(FlowTrace trace) {
        return new FlowTraceAssert(trace);
    }
}
