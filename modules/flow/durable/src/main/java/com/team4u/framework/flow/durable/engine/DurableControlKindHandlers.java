package com.team4u.framework.flow.durable.engine;


import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.team4u.framework.flow.durable.DurableMachine;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.spi.ControlKind;

/**
 * Durable 治理控制类型策略实现族。
 *
 * @author jay.wu
 */
public final class DurableControlKindHandlers {
    private DurableControlKindHandlers() { }

    private static void requirePhase(DurableState.RuntimeFrame frame,
                                     DurablePlanNode.Control control, int expected, String kind) {
        if (frame.phase != expected) {
            throw new IllegalStateException(kind + " child frame is missing at "
                    + control.descriptor().path());
        }
    }

    static final class TimeoutHandler implements DurableControlKindHandler {
        @Override
        public ControlKind key() {
            return ControlKind.TIMEOUT;
        }

        @Override
        public void enter(DurablePlanNode.Control control, DurableState.RuntimeFrame frame, DurableMachine machine) {
            requirePhase(frame, control, 0, "Timeout");
            frame.deadline = Instant.now().plus((Duration) control.configuration());
            frame.phase = 1;
            machine.push(control.body(), frame.entry, frame.entryRole);
        }

        @Override
        public DurableState.MachineOutcome reduce(DurablePlanNode.Control control, DurableState.RuntimeFrame frame,
                                                  DurableState.MachineOutcome child, DurableMachine machine) {
            boolean timedOut = frame.deadline != null && !Instant.now().isBefore(frame.deadline);
            frame.deadline = null;
            return timedOut
                    ? DurableState.MachineOutcome.of(machine.timeoutFailure())
                    : child;
        }
    }

    static final class RetryHandler implements DurableControlKindHandler {
        @Override
        public ControlKind key() {
            return ControlKind.RETRY;
        }

        @Override
        public void enter(DurablePlanNode.Control control, DurableState.RuntimeFrame frame, DurableMachine machine) {
            if (frame.phase == 0 || frame.phase == 2) {
                if (frame.phase == 2 && frame.wake != null
                        && Instant.now().isBefore(frame.wake)) {
                    machine.waitOrPark(frame, control);
                    return;
                }
                frame.wake = null;
                frame.phase = 1;
                machine.push(control.body(), frame.entry, frame.entryRole);
                return;
            }
            throw new IllegalStateException("Retry body frame missing at " + control.descriptor().path());
        }

        @Override
        public DurableState.MachineOutcome reduce(DurablePlanNode.Control control, DurableState.RuntimeFrame frame,
                                                  DurableState.MachineOutcome child, DurableMachine machine) {
            Retry retry = (Retry) control.configuration();
            if (child.outcome() instanceof Outcome.Failed
                    && frame.attempt < retry.maxAttempts()) {
                frame.attempt++;
                frame.wake = Instant.now().plus(retry.backoff());
                frame.phase = 2;
                machine.commitCheckpoint(CheckpointReasons.control(control.descriptor().path()));
                machine.waitingEvent(control, frame);
                return null;
            }
            return child;
        }
    }

    static final class PolicyHandler implements DurableControlKindHandler {
        @Override
        public ControlKind key() {
            return ControlKind.POLICY;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void enter(final DurablePlanNode.Control control, final DurableState.RuntimeFrame frame, final DurableMachine machine) {
            requirePhase(frame, control, 0, "Policy");
            Policy<Object> policy = (Policy<Object>) control.binding().get().instance();
            machine.event(FlowObserver.Type.POLICY_BEFORE, control.descriptor(),
                    Collections.singletonMap("attempt", Integer.toString(frame.attempt)));
            Object key;
            Gate gate;
            try {
                key = Objects.requireNonNull(control.keyProjection().apply(frame.entry),
                        "policy key must not be null");
                gate = Objects.requireNonNull(
                        policy.before(machine.policyContext(control, frame), key),
                        "policy gate must not be null");
            } catch (Exception error) {
                machine.finish(DurableState.MachineOutcome.of(DurableMachine.policyFailure(error)),
                        CheckpointReasons.control(control.descriptor().path()));
                return;
            }
            frame.key = key;
            if (gate instanceof Gate.Proceed) {
                frame.phase = 1;
                machine.push(control.body(), frame.entry, frame.entryRole);
                return;
            }
            if (gate instanceof Gate.Reject) {
                machine.finish(DurableState.MachineOutcome.of(
                                Outcome.rejected(((Gate.Reject) gate).reason())),
                        CheckpointReasons.control(control.descriptor().path()));
                return;
            }
            if (gate instanceof Gate.Fail) {
                machine.finish(DurableState.MachineOutcome.of(
                                Outcome.failed(((Gate.Fail) gate).failure())),
                        CheckpointReasons.control(control.descriptor().path()));
                return;
            }
            throw new IllegalStateException("Unknown Gate decision: " + gate.getClass().getName());
        }

        @Override
        @SuppressWarnings("unchecked")
        public DurableState.MachineOutcome reduce(final DurablePlanNode.Control control, final DurableState.RuntimeFrame frame,
                                                  final DurableState.MachineOutcome child, final DurableMachine machine) {
            Policy<Object> policy = (Policy<Object>) control.binding().get().instance();
            try {
                policy.after(machine.policyContext(control, frame), frame.key,
                        CompletionAdapter.from(child.outcome()));
            } catch (Exception error) {
                machine.event(FlowObserver.Type.POLICY_AFTER, control.descriptor(),
                        machine.policyAfterAttrs(frame, child));
                return DurableState.MachineOutcome.of(DurableMachine.policyFailure(error));
            }
            machine.event(FlowObserver.Type.POLICY_AFTER, control.descriptor(),
                    machine.policyAfterAttrs(frame, child));
            return child;
        }
    }

    static final class PersistentPolicyHandler implements DurableControlKindHandler {
        @Override
        public ControlKind key() {
            return ControlKind.PERSISTENT_POLICY;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void enter(final DurablePlanNode.Control control, final DurableState.RuntimeFrame frame, final DurableMachine machine) {
            PersistentPolicy<Object, Object> policy =
                    (PersistentPolicy<Object, Object>) control.binding().get().instance();
            if (frame.phase == 2 || frame.phase == 3) {
                if (frame.wake != null && Instant.now().isBefore(frame.wake)) {
                    machine.waitOrPark(frame, control);
                    return;
                }
                frame.wake = null;
                frame.phase = 0;
            }
            if (frame.phase != 0) {
                throw new IllegalStateException(
                        "PersistentPolicy body frame missing at " + control.descriptor().path());
            }
            machine.event(FlowObserver.Type.POLICY_BEFORE, control.descriptor(),
                    Collections.singletonMap("attempt", Integer.toString(frame.attempt)));
            Object key;
            Object currentState;
            PersistentPolicy.Before<Object> decision;
            try {
                key = frame.key == null
                        ? Objects.requireNonNull(control.keyProjection().apply(frame.entry),
                        "policy key must not be null")
                        : frame.key;
                currentState = frame.policyState == null
                        ? Objects.requireNonNull(policy.initialState(key),
                        "policy initial state must not be null")
                        : frame.policyState;
                decision = Objects.requireNonNull(
                        policy.before(machine.policyContext(control, frame), key, currentState),
                        "policy before decision must not be null");
            } catch (Exception error) {
                machine.finish(DurableState.MachineOutcome.of(DurableMachine.policyFailure(error)),
                        CheckpointReasons.control(control.descriptor().path()));
                return;
            }
            frame.key = key;
            frame.policyState = currentState;
            if (decision instanceof PersistentPolicy.Proceed) {
                PersistentPolicy.Proceed<Object> proceed =
                        (PersistentPolicy.Proceed<Object>) decision;
                frame.policyState = proceed.state();
                frame.phase = 1;
                machine.push(control.body(), frame.entry, frame.entryRole);
                machine.commitCheckpoint(CheckpointReasons.control(control.descriptor().path()));
                return;
            }
            if (decision instanceof PersistentPolicy.WaitUntil) {
                PersistentPolicy.WaitUntil<Object> wait =
                        (PersistentPolicy.WaitUntil<Object>) decision;
                frame.policyState = wait.state();
                frame.wake = wait.instant();
                frame.phase = 2;
                machine.commitCheckpoint(CheckpointReasons.control(control.descriptor().path()));
                machine.waitingEvent(control, frame);
                return;
            }
            if (decision instanceof PersistentPolicy.Reject) {
                PersistentPolicy.Reject<Object> reject =
                        (PersistentPolicy.Reject<Object>) decision;
                frame.policyState = reject.state();
                machine.finish(DurableState.MachineOutcome.of(Outcome.rejected(reject.reason())),
                        CheckpointReasons.control(control.descriptor().path()));
                return;
            }
            if (decision instanceof PersistentPolicy.Fail) {
                PersistentPolicy.Fail<Object> fail = (PersistentPolicy.Fail<Object>) decision;
                frame.policyState = fail.state();
                machine.finish(DurableState.MachineOutcome.of(Outcome.failed(fail.failure())),
                        CheckpointReasons.control(control.descriptor().path()));
                return;
            }
            throw new IllegalStateException("Unknown PersistentPolicy.Before decision: "
                    + decision.getClass().getName());
        }

        @Override
        @SuppressWarnings("unchecked")
        public DurableState.MachineOutcome reduce(final DurablePlanNode.Control control, final DurableState.RuntimeFrame frame,
                                                  final DurableState.MachineOutcome child, final DurableMachine machine) {
            PersistentPolicy<Object, Object> policy =
                    (PersistentPolicy<Object, Object>) control.binding().get().instance();
            PersistentPolicy.After<Object> decision;
            try {
                decision = Objects.requireNonNull(
                        policy.after(machine.policyContext(control, frame), frame.key,
                                frame.policyState, CompletionAdapter.from(child.outcome())),
                        "policy after decision must not be null");
            } catch (Exception error) {
                machine.event(FlowObserver.Type.POLICY_AFTER, control.descriptor(),
                        machine.policyAfterAttrs(frame, child));
                return DurableState.MachineOutcome.of(DurableMachine.policyFailure(error));
            }
            machine.event(FlowObserver.Type.POLICY_AFTER, control.descriptor(),
                    machine.policyAfterAttrs(frame, child));
            if (decision instanceof PersistentPolicy.Return) {
                frame.policyState = ((PersistentPolicy.Return<Object>) decision).state();
                return child;
            }
            if (decision instanceof PersistentPolicy.RetryAt) {
                PersistentPolicy.RetryAt<Object> retryAt =
                        (PersistentPolicy.RetryAt<Object>) decision;
                frame.policyState = retryAt.state();
                frame.wake = retryAt.instant();
                frame.attempt++;
                frame.phase = 3;
                machine.commitCheckpoint(CheckpointReasons.control(control.descriptor().path()));
                machine.waitingEvent(control, frame);
                return null;
            }
            throw new IllegalStateException("Unknown PersistentPolicy.After decision: "
                    + decision.getClass().getName());
        }
    }
}
