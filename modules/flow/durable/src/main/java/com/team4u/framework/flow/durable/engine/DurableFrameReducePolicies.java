package com.team4u.framework.flow.durable.engine;


import java.util.Collections;
import java.util.Objects;
import com.team4u.framework.flow.durable.DurableMachine;
import com.team4u.framework.flow.spi.FallbackTrigger;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.model.FlowDiagnosticCodes;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.model.Recovery;

/**
 * Durable 运行时父帧结果归约策略实现族。
 *
 * @author jay.wu
 */
public final class DurableFrameReducePolicies {
    private DurableFrameReducePolicies() { }

    static final class SequenceReducePolicy implements DurableFrameReducePolicy<DurablePlanNode.Sequence> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Sequence.class;
        }

        @Override
        public DurableState.MachineOutcome reduce(DurablePlanNode.Sequence sequence, DurableState.RuntimeFrame frame,
                                                  DurableState.MachineOutcome child, DurableMachine machine) {
            if (!(child.outcome() instanceof Outcome.Accepted)) {
                return child;
            }
            Outcome.Accepted<?> accepted = (Outcome.Accepted<?>) child.outcome();
            frame.current = accepted.value();
            frame.currentRole = child.acceptedRole();
            frame.index++;
            if (frame.index >= sequence.children().size()) {
                return DurableState.MachineOutcome.accepted(frame.current, frame.currentRole);
            }
            machine.push(sequence.children().get(frame.index), frame.current, frame.currentRole);
            return null;
        }
    }

    static final class RouteReducePolicy implements DurableFrameReducePolicy<DurablePlanNode.Route> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Route.class;
        }

        @Override
        public DurableState.MachineOutcome reduce(DurablePlanNode.Route route, DurableState.RuntimeFrame frame,
                                                  DurableState.MachineOutcome child, DurableMachine machine) {
            if (frame.phase == 1) {
                if (!(child.outcome() instanceof Outcome.Accepted)) {
                    return child;
                }
                Object key = ((Outcome.Accepted<?>) child.outcome()).value();
                java.util.List<DurablePlanNode.Route.RouteCase> cases = route.cases();
                int selected = -1;
                for (int index = 0; index < cases.size(); index++) {
                    if (cases.get(index).key().equals(key)) {
                        selected = index;
                        break;
                    }
                }
                frame.phase = 2;
                frame.index = selected;
                if (selected >= 0) {
                    frame.selected = "case:" + selected;
                    machine.push(cases.get(selected).branch(), frame.entry, frame.entryRole);
                    machine.event(FlowObserver.Type.ROUTE_SELECTED, route.descriptor(),
                            Collections.singletonMap("branch", frame.selected));
                    return null;
                }
                if (route.otherwise() != null) {
                    frame.selected = "otherwise";
                    machine.push(route.otherwise(), frame.entry, frame.entryRole);
                    machine.event(FlowObserver.Type.ROUTE_SELECTED, route.descriptor(),
                            Collections.singletonMap("branch", frame.selected));
                    return null;
                }
                return DurableState.MachineOutcome.of(Outcome.skipped(
                        Reason.of(FlowDiagnosticCodes.NO_ROUTE, "No route case matched the selector")));
            }
            if (frame.phase == 2) {
                return child;
            }
            throw new IllegalStateException("Invalid Route phase at " + route.descriptor().path());
        }
    }

    static final class FallbackReducePolicy implements DurableFrameReducePolicy<DurablePlanNode.Fallback> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Fallback.class;
        }

        @Override
        public DurableState.MachineOutcome reduce(DurablePlanNode.Fallback fallback, DurableState.RuntimeFrame frame,
                                                  DurableState.MachineOutcome child, DurableMachine machine) {
            boolean skippedTrigger = fallback.trigger() == com.team4u.framework.flow.spi.FallbackTrigger.SKIPPED;
            boolean triggered = skippedTrigger
                    ? child.outcome() instanceof Outcome.Skipped
                    : child.outcome() instanceof Outcome.Failed;
            if (!triggered || frame.index + 1 >= fallback.branches().size()) {
                return child;
            }
            frame.index++;
            Object input = frame.entry;
            DurableState.SlotRole inputRole = frame.entryRole;
            if (!skippedTrigger) {
                Outcome.Failed<?> failed = (Outcome.Failed<?>) child.outcome();
                input = new Recovery<Object>(frame.entry, failed.failure());
                inputRole = new DurableState.SlotRole.Recovery(frame.entryRole);
            }
            frame.selected = "branch:" + frame.index;
            machine.push(fallback.branches().get(frame.index), input, inputRole);
            machine.event(FlowObserver.Type.FALLBACK_SELECTED, fallback.descriptor(),
                    Collections.singletonMap("branch", frame.selected));
            return null;
        }
    }

    static final class ControlReducePolicy implements DurableFrameReducePolicy<DurablePlanNode.Control> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Control.class;
        }

        @Override
        public DurableState.MachineOutcome reduce(DurablePlanNode.Control control, DurableState.RuntimeFrame frame,
                                                  DurableState.MachineOutcome child, DurableMachine machine) {
            DurableControlKindHandler handler = DurableControlKindRegistry.global().get(control.kind())
                    .orElseThrow(() -> new IllegalStateException("Unknown control kind: " + control.kind()));
            return handler.reduce(control, frame, child, machine);
        }
    }

    static final class AdapterReducePolicy implements DurableFrameReducePolicy<DurablePlanNode.Adapter> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Adapter.class;
        }

        @Override
        public DurableState.MachineOutcome reduce(DurablePlanNode.Adapter adapter, DurableState.RuntimeFrame frame,
                                                  DurableState.MachineOutcome child, DurableMachine machine) {
            if (frame.phase == 1) {
                if (!(child.outcome() instanceof Outcome.Accepted)) {
                    return child;
                }
                Outcome.Accepted<?> accepted = (Outcome.Accepted<?>) child.outcome();
                try {
                    Object merged = Objects.requireNonNull(
                            adapter.merge().apply(frame.entry, accepted.value()),
                            "merged output must not be null");
                    return DurableState.MachineOutcome.accepted(merged,
                            DurableState.SlotRole.user(DurablePlanCompiler.nodeRole(adapter.descriptor().path())));
                } catch (com.team4u.framework.flow.model.FlowExecutionException fee) {
                    return DurableState.MachineOutcome.of(Outcome.failed(com.team4u.framework.flow.model.Failure.of(fee.code(), fee.getMessage())));
                } catch (Exception e) {
                    return DurableState.MachineOutcome.of(Outcome.failed(com.team4u.framework.flow.model.Failure.of(
                            com.team4u.framework.flow.model.FlowDiagnosticCodes.ADAPTER_MERGE_EXCEPTION,
                            e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()))));
                }
            }
            throw new IllegalStateException("Invalid Adapter phase at " + adapter.descriptor().path());
        }
    }
}
