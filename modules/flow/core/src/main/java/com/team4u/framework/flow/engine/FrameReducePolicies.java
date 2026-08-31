package com.team4u.framework.flow.engine;

import java.util.Collections;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.model.Recovery;

/**
 * 运行时父帧结果归约策略实现族。
 *
 * @author jay.wu
 */
public final class FrameReducePolicies {
    private FrameReducePolicies() { }

    static final class SequenceReducePolicy implements FrameReducePolicy<PlanNode.Sequence> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Sequence.class;
        }

        @Override
        public Outcome<?> reduce(PlanNode.Sequence sequence, SerialMachine machine, RuntimeFrame frame, Outcome<?> child) {
            if (!(child instanceof Outcome.Accepted)) return child;
            Outcome.Accepted<?> accepted = (Outcome.Accepted<?>) child;
            frame.current = accepted.value();
            frame.index++;
            if (frame.index >= sequence.children().size()) {
                return Outcome.accepted(frame.current);
            }
            machine.push(sequence.children().get(frame.index), frame.current);
            return null;
        }
    }

    static final class RouteReducePolicy implements FrameReducePolicy<PlanNode.Route> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Route.class;
        }

        @Override
        public Outcome<?> reduce(PlanNode.Route route, SerialMachine machine, RuntimeFrame frame, Outcome<?> child) {
            if (frame.phase == 1) {
                if (!(child instanceof Outcome.Accepted)) return child;
                Outcome.Accepted<?> accepted = (Outcome.Accepted<?>) child;
                int selected = -1;
                for (int index = 0; index < route.cases().size(); index++) {
                    if (route.cases().get(index).key().equals(accepted.value())) {
                        selected = index;
                        break;
                    }
                }
                frame.phase = 2;
                frame.index = selected;
                if (selected >= 0) {
                    frame.selected = "case:" + selected;
                    machine.push(route.cases().get(selected).branch(), frame.entry);
                } else if (route.otherwise() != null) {
                    frame.selected = "otherwise";
                    machine.push(route.otherwise(), frame.entry);
                } else {
                    return Outcome.skipped(Reason.of("NO_ROUTE",
                            "No route case matched the selector"));
                }
                machine.event(FlowObserver.Type.ROUTE_SELECTED, route.descriptor(),
                        Collections.singletonMap("branch", frame.selected));
                return null;
            }
            if (frame.phase == 2) return child;
            throw new IllegalStateException("Invalid Route phase at " + route.descriptor().path());
        }
    }

    static final class FallbackReducePolicy implements FrameReducePolicy<PlanNode.Fallback> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Fallback.class;
        }

        @Override
        public Outcome<?> reduce(PlanNode.Fallback fallback, SerialMachine machine, RuntimeFrame frame, Outcome<?> child) {
            boolean triggered = fallback.trigger() == PlanNode.Fallback.Trigger.SKIPPED
                    ? child instanceof Outcome.Skipped
                    : child instanceof Outcome.Failed;
            if (!triggered || frame.index + 1 >= fallback.branches().size()) return child;
            frame.index++;
            Object input = frame.entry;
            if (fallback.trigger() == PlanNode.Fallback.Trigger.FAILED) {
                input = new Recovery<Object>(frame.entry, ((Outcome.Failed<?>) child).failure());
            }
            frame.selected = "branch:" + frame.index;
            machine.push(fallback.branches().get(frame.index), input);
            machine.event(FlowObserver.Type.FALLBACK_SELECTED, fallback.descriptor(),
                    Collections.singletonMap("branch", frame.selected));
            return null;
        }
    }

    static final class ControlReducePolicy implements FrameReducePolicy<PlanNode.Control> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Control.class;
        }

        @Override
        public Outcome<?> reduce(PlanNode.Control control, SerialMachine machine, RuntimeFrame frame, Outcome<?> child) {
            ControlKindHandler handler = ControlKindRegistry.global().get(control.kind())
                    .orElseThrow(() -> new IllegalStateException("Unknown control kind: " + control.kind()));
            return handler.reduce(control, frame, machine, child);
        }
    }
}
