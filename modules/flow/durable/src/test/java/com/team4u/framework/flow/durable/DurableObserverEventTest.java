package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PersistentPolicy;
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

    @Test
    public void recoverEmitsCheckpointRestoredWithRevision() {
        // recover 必须发布 CHECKPOINT_RESTORED，携带恢复时的快照版本号与 lifecycle
        DurableTestOps.RecordingDurableObserver durableObserver =
                new DurableTestOps.RecordingDurableObserver();
        InMemoryDurableStore store = new InMemoryDurableStore();
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.failed(Failure.of("X", "x"));
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .persistentPolicy(new PersistentPolicy<String, Integer>() {
                    @Override
                    public Integer initialState(String key) {
                        return 1;
                    }

                    @Override
                    public PersistentPolicy.Before<Integer> before(
                            com.team4u.framework.flow.api.PolicyContext ctx, String key,
                            Integer state) {
                        return PersistentPolicy.proceed(state);
                    }

                    @Override
                    public PersistentPolicy.After<Integer> after(
                            com.team4u.framework.flow.api.PolicyContext ctx, String key,
                            Integer state, com.team4u.framework.flow.model.Completion completion) {
                        return PersistentPolicy.retryAt(
                                java.time.Instant.now().plusMillis(60_000), state + 1);
                    }
                }, s -> s);
        DurableExecutable<String, String> executable = DurableRuntime.builder(store)
                .durableObserver(durableObserver)
                .build()
                .compile(flow, "obs", 1);
        executable.start("e", "in");
        long parkedRevision = store.load("e").get().revision();
        List<DurableObserver.Event> before = durableObserver.byType(
                DurableObserver.Type.CHECKPOINT_RESTORED);
        assertTrue("start 不应发布 CHECKPOINT_RESTORED", before.isEmpty());

        DurableResult<String> recovered = executable.recover("e");
        assertTrue(recovered.getClass().getSimpleName(), recovered instanceof DurableResult.Active);
        List<DurableObserver.Event> restored = durableObserver.byType(
                DurableObserver.Type.CHECKPOINT_RESTORED);
        assertEquals("recover 必须发布恰好一条 CHECKPOINT_RESTORED", 1, restored.size());
        DurableObserver.Event event = restored.get(0);
        assertEquals("事件必须携带恢复时的快照版本号",
                parkedRevision, event.revision());
        assertEquals(DurableLifecycle.ACTIVE, event.lifecycle());
        assertEquals("e", event.metadata().executionId());
    }
}
