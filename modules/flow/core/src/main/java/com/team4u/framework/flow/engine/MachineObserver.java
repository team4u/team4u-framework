package com.team4u.framework.flow.engine;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Metadata;
import com.team4u.framework.flow.api.ObserverSafeEmitter;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.spi.NodeDescriptor;

/**
 * 流程状态机事件发布适配器（Machine Event Dispatcher）。
 *
 * <p>封装对 {@link FlowObserver} 的事件派发逻辑，负责构造规范化的 {@link Metadata} 上下文、节点属性，并提供异常隔离保护。</p>
 *
 * @author jay.wu
 */
public final class MachineObserver {
    private final String flowId;
    private final int flowVersion;
    private final MachineState state;
    private final FlowObserver observer;

    MachineObserver(String flowId, int flowVersion, MachineState state,
                    FlowObserver observer) {
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.state = state;
        this.observer = observer;
    }


    /** 帧首次进入时发布 NODE_STARTED（observerStarted 去重保证幂等）。 */
    void nodeStarted(RuntimeFrame frame) {
        if (observer.isNoop() || frame.observerStarted) return;
        frame.observerStarted = true;
        event(FlowObserver.Type.NODE_STARTED, frame.node.descriptor(),
                attributes(frame, null));
    }

    void nodeCompleted(RuntimeFrame frame, Outcome<?> outcome) {
        if (observer.isNoop()) return;
        if (frame.node instanceof PlanNode.Invoke) return;
        event(FlowObserver.Type.NODE_COMPLETED, frame.node.descriptor(),
                attributes(frame, outcome));
    }

    void event(FlowObserver.Type type, NodeDescriptor descriptor,
               Map<String, String> attributes) {
        if (observer.isNoop()) return;
        Metadata metadata = new Metadata(flowId, flowVersion, state.executionId(),
                descriptor.path(), descriptor.label());
        ObserverSafeEmitter.emit(observer, new FlowObserver.Event(type, Instant.now(),
                metadata, descriptor, attributes));
    }

    private static Map<String, String> attributes(RuntimeFrame frame,
                                                   Outcome<?> outcome) {
        if (outcome == null && !(frame.node instanceof PlanNode.Sequence)
                && !(frame.node instanceof PlanNode.Control) && frame.selected == null) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> attributes = new LinkedHashMap<String, String>();
        if (frame.node instanceof PlanNode.Sequence) {
            PlanNode.Sequence sequence = (PlanNode.Sequence) frame.node;
            if (sequence.scopeName() != null) {
                attributes.put("scope", sequence.scopeName());
            }
        }
        if (frame.node instanceof PlanNode.Control) {
            attributes.put("attempt", Integer.toString(frame.attempt));
        }
        if (frame.selected != null) {
            attributes.put("branch", frame.selected);
        }
        if (outcome != null) {
            attributes.put("outcome", outcome.kind().name());
            String code = diagnosticCode(outcome);
            if (!code.isEmpty()) {
                attributes.put("code", code);
            }
        }
        return Collections.unmodifiableMap(attributes);
    }

    private static String diagnosticCode(Outcome<?> outcome) {
        if (outcome instanceof Outcome.Rejected) {
            return ((Outcome.Rejected<?>) outcome).reason().code();
        } else if (outcome instanceof Outcome.Skipped) {
            return ((Outcome.Skipped<?>) outcome).reason().code();
        } else if (outcome instanceof Outcome.Failed) {
            return ((Outcome.Failed<?>) outcome).failure().code();
        }
        return "";
    }
}
