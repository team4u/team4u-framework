package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Failure;
import com.team4u.framework.flow.FlowObserver;
import com.team4u.framework.flow.Metadata;
import com.team4u.framework.flow.NodeDescriptor;
import com.team4u.framework.flow.Outcome;
import com.team4u.framework.flow.Reason;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable 状态机执行事件与观察者分发器。
 *
 * @author jay.wu
 */
final class DurableMachineObserver {
    private final String flowId;
    private final int flowVersion;
    private final DurableState.MachineState state;
    private final FlowObserver observer;

    DurableMachineObserver(String flowId, int flowVersion, DurableState.MachineState state, FlowObserver observer) {
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.state = state;
        this.observer = observer;
    }

    void nodeStarted(DurableState.RuntimeFrame frame) {
        event(FlowObserver.Type.NODE_STARTED, frame.node.descriptor(),
                frame.selected == null
                        ? Collections.<String, String>emptyMap()
                        : Collections.singletonMap("branch", frame.selected));
    }

    void nodeCompleted(DurableState.RuntimeFrame frame, DurableState.MachineOutcome outcome) {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("outcome", outcome.outcome().kind().name());
        String code = diagnosticCode(outcome.outcome());
        if (!code.isEmpty()) {
            attrs.put("code", code);
        }
        event(FlowObserver.Type.NODE_COMPLETED, frame.node.descriptor(),
                Collections.unmodifiableMap(attrs));
    }

    void invokeCompleted(DurablePlanNode.Invoke node, Outcome<?> outcome, long durationNanos) {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("outcome", outcome.kind().name());
        attrs.put("durationNanos", Long.toString(durationNanos));
        String code = diagnosticCode(outcome);
        if (!code.isEmpty()) {
            attrs.put("code", code);
        }
        event(FlowObserver.Type.NODE_COMPLETED, node.descriptor(),
                Collections.unmodifiableMap(attrs));
    }

    void waitingEvent(DurablePlanNode.Control control, DurableState.RuntimeFrame frame) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("attempt", Integer.toString(frame.attempt));
        attrs.put("wake", frame.wake.toString());
        event(FlowObserver.Type.POLICY_WAITING, control.descriptor(), attrs);
    }

    void event(FlowObserver.Type type, NodeDescriptor descriptor, Map<String, String> attributes) {
        try {
            observer.onEvent(new FlowObserver.Event(type, Instant.now(),
                    new Metadata(flowId, flowVersion, state.executionId,
                            descriptor.path(), descriptor.label()),
                    descriptor, attributes));
        } catch (RuntimeException ignored) {
            // Observers cannot alter execution.
        }
    }

    static String diagnosticCode(Outcome<?> outcome) {
        if (outcome instanceof Outcome.Rejected) {
            Reason reason = ((Outcome.Rejected<?>) outcome).reason();
            return reason == null || reason.code() == null ? "" : reason.code();
        }
        if (outcome instanceof Outcome.Skipped) {
            Reason reason = ((Outcome.Skipped<?>) outcome).reason();
            return reason == null || reason.code() == null ? "" : reason.code();
        }
        if (outcome instanceof Outcome.Failed) {
            Failure failure = ((Outcome.Failed<?>) outcome).failure();
            return failure == null || failure.code() == null ? "" : failure.code();
        }
        return "";
    }
}
