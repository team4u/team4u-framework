package com.team4u.framework.flow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 挂起点取消竞态守护：FLOW_SUSPENDED 事件窗口内触发的取消必须落定 CANCELLED，
 * 不得返回 SUSPENDED；正常挂起（无取消）语义不变。
 */
public class SuspendCancellationRaceTest {

    @Test
    public void cancellationDuringFlowSuspendedEventSettlesCancelled() {
        ResumePoint<String> point = ResumePoint.named("race-cancel");
        Flow<String, Resumed<String, String>> flow = Flow.<String>identity().await(point);
        final Cancellation cancellation = Cancellation.create();
        final List<FlowObserver.Event> events = new ArrayList<FlowObserver.Event>();
        FlowObserver observer = event -> {
            events.add(event);
            if (event.type() == FlowObserver.Type.FLOW_SUSPENDED) {
                cancellation.cancel();
            }
        };
        FlowResult<Resumed<String, String>> result;
        try {
            result = Local.compile(flow, OperationResolver.rejecting(), observer)
                    .run("input", cancellation);
        } finally {
            Thread.interrupted();
        }
        assertTrue("expected Cancelled but was " + result.getClass().getSimpleName(),
                result instanceof FlowResult.Cancelled<?>);
        assertEquals(FlowObserver.Type.FLOW_CANCELLED,
                events.get(events.size() - 1).type());
        assertEquals(FlowObserver.Type.FLOW_SUSPENDED,
                events.get(events.size() - 2).type());
    }

    @Test
    public void suspensionWithoutCancellationStillReturnsSuspended() {
        ResumePoint<String> point = ResumePoint.named("plain-suspend");
        Flow<String, Resumed<String, String>> flow = Flow.<String>identity().await(point);
        FlowResult<Resumed<String, String>> result = Local.compile(flow).run("input");
        assertTrue(result instanceof FlowResult.Suspended<?>);
    }
}
