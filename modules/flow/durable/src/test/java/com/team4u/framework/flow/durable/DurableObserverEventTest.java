package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;

/**
 * 组12：观察者契约 — Invoke 节点的 NODE_STARTED 与 NODE_COMPLETED 成对发布，
 * 完成事件属性与 Core InvocationRunner 对齐（outcome kind、durationNanos、
 * 非 Accepted 时的 code）。
 */
public class DurableObserverEventTest {

    /** 按节点路径记录事件的收集观察者。 */
    private static final class Collector implements FlowObserver {
        final List<Event> events = new ArrayList<Event>();

        @Override
        public void onEvent(Event event) {
            events.add(event);
        }

        List<Event> byType(Type type) {
            List<Event> found = new ArrayList<Event>();
            for (Event event : events) {
                if (event.type() == type) {
                    found.add(event);
                }
            }
            return found;
        }

        Event first(Type type, String path) {
            for (Event event : byType(type)) {
                if (event.metadata().nodePath().equals(path)) {
                    return event;
                }
            }
            return null;
        }
    }

    private static Operation<String, String> op(final String tag, final Outcome<String> fixed) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return fixed == null ? Outcome.accepted(input + ">" + tag) : fixed;
            }
        };
    }

    @Test
    public void invokeEmitsPairedStartedAndCompletedEvents() {
        Collector observer = new Collector();
        Flow<String, String> flow = Flow.<String, String>step(op("a", null));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableRuntime.builder(store).observer(observer).build()
                .compile(flow, "obs", 1).start("e", "in");
        // 单 step 流的根即 invoke：路径为 "$"
        FlowObserver.Event started = observer.first(FlowObserver.Type.NODE_STARTED, "$");
        FlowObserver.Event completed = observer.first(FlowObserver.Type.NODE_COMPLETED, "$");
        assertNotNull("Invoke 必须发布 NODE_STARTED", started);
        assertNotNull("Invoke 必须发布 NODE_COMPLETED", completed);
        assertEquals("ACCEPTED", completed.attributes().get("outcome"));
        assertNotNull("durationNanos 必须存在",
                completed.attributes().get("durationNanos"));
        assertTrue("durationNanos 必须非负",
                Long.parseLong(completed.attributes().get("durationNanos")) >= 0);
        assertEquals("Accepted 完成事件不携带诊断 code",
                null, completed.attributes().get("code"));
    }

    @Test
    public void invokeCompletedCarriesDiagnosticCodeForNonAccepted() {
        Collector observer = new Collector();
        Flow<String, String> flow = Flow.<String, String>step(op("bad",
                Outcome.failed(Failure.of("OP_ERR", "boom"))));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableRuntime.builder(store).observer(observer).build()
                .compile(flow, "obs", 1).start("e", "in");
        FlowObserver.Event completed = observer.first(FlowObserver.Type.NODE_COMPLETED, "$");
        assertNotNull(completed);
        assertEquals("FAILED", completed.attributes().get("outcome"));
        assertEquals("非 Accepted 必须携带 code", "OP_ERR",
                completed.attributes().get("code"));
        assertNotNull(completed.attributes().get("durationNanos"));
    }

    @Test
    public void invokeSequenceEmitsPairedEventsPerNode() {
        Collector observer = new Collector();
        Flow<String, String> flow = Flow.<String, String>step(op("a", null))
                .then(op("b", null));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableRuntime.builder(store).observer(observer).build()
                .compile(flow, "obs", 1).start("e", "in");
        // 根 sequence（"$"）与两个 invoke（"$/0"、"$/1"）：invoke 事件成对
        for (String path : java.util.Arrays.asList("$/0", "$/1")) {
            assertNotNull(path, observer.first(FlowObserver.Type.NODE_STARTED, path));
            assertNotNull(path, observer.first(FlowObserver.Type.NODE_COMPLETED, path));
        }
        assertEquals(3, observer.byType(FlowObserver.Type.NODE_STARTED).size());
        assertEquals(3, observer.byType(FlowObserver.Type.NODE_COMPLETED).size());
    }
}
