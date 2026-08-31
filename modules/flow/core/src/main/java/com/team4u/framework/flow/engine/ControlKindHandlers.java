package com.team4u.framework.flow.engine;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Outcome;

/**
 * 治理控制类型（TIMEOUT / RETRY / POLICY / PERSISTENT_POLICY）策略实现族。
 *
 * @author jay.wu
 */
public final class ControlKindHandlers {
    private ControlKindHandlers() { }

    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    private static final class PolicyBefore {
        private final Object key;
        private final Gate gate;
    }

    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    private static final class PersistentBefore {
        private final Object key;
        private final Object state;
        private final PersistentPolicy.Before<Object> decision;
    }

    private static void requirePhase(RuntimeFrame frame, PlanNode.Control control,
                                     int expected, String kind) {
        if (frame.phase != expected) throw new IllegalStateException(kind
                + " child frame is missing at " + control.descriptor().path());
    }

    private static MachineResult complete(SerialMachine machine, Outcome<?> outcome) {
        machine.finish(outcome);
        return null;
    }

    private static MachineResult callbackFailure(SerialMachine machine, Throwable failure) {
        if (machine.cancelled()) {
            machine.cancel();
            return machine.result();
        }
        return complete(machine, machine.policyFailure(failure));
    }

    private static Outcome<?> reducerCallbackFailure(SerialMachine machine, Throwable failure) {
        if (machine.cancelled()) throw new CancellationException(
                "flow execution was cancelled");
        return machine.policyFailure(failure);
    }

    static final class TimeoutHandler implements ControlKindHandler {
        @Override
        public PlanNode.Control.Kind key() {
            return PlanNode.Control.Kind.TIMEOUT;
        }

        @Override
        public MachineResult enter(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine) {
            requirePhase(frame, control, 0, "Timeout");
            frame.deadline = Instant.now().plus((Duration) control.configuration());
            frame.phase = 1;
            machine.push(control.body(), frame.entry);
            return null;
        }

        @Override
        public Outcome<?> reduce(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine, Outcome<?> child) {
            boolean timedOut = frame.deadline != null && !Instant.now().isBefore(frame.deadline);
            frame.deadline = null;
            return timedOut ? SerialMachine.timeoutFailure() : child;
        }
    }

    static final class RetryHandler implements ControlKindHandler {
        @Override
        public PlanNode.Control.Kind key() {
            return PlanNode.Control.Kind.RETRY;
        }

        @Override
        public MachineResult enter(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine) {
            if (frame.phase == 0) {
                frame.phase = 1;
                machine.push(control.body(), frame.entry);
                return null;
            }
            requirePhase(frame, control, 2, "Retry");
            MachineResult waiting = machine.awaitWake(frame);
            if (waiting != null) return waiting;
            frame.phase = 1;
            machine.push(control.body(), frame.entry);
            return null;
        }

        @Override
        public Outcome<?> reduce(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine, Outcome<?> child) {
            Retry retry = (Retry) control.configuration();
            if (child instanceof Outcome.Failed && frame.attempt < retry.maxAttempts()) {
                frame.attempt++;
                frame.wake = Instant.now().plus(retry.backoff());
                frame.phase = 2;
                machine.waitingEvent(control, frame);
                return null;
            }
            return child;
        }
    }

    static final class PolicyHandler implements ControlKindHandler {
        @Override
        public PlanNode.Control.Kind key() {
            return PlanNode.Control.Kind.POLICY;
        }

        @Override
        @SuppressWarnings("unchecked")
        public MachineResult enter(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine) {
            requirePhase(frame, control, 0, "Policy");
            Policy<Object> policy = (Policy<Object>) control.policy().instance();
            machine.event(FlowObserver.Type.POLICY_BEFORE, control.descriptor(),
                    Collections.singletonMap("attempt", Integer.toString(frame.attempt)));
            CallbackRunner.Result<PolicyBefore> call = machine.callbacks().call(signal -> {
                Object key = Objects.requireNonNull(control.keyProjection().apply(frame.entry),
                        "policy key must not be null");
                Gate gate = Objects.requireNonNull(
                        policy.before(machine.context(frame, control, signal), key),
                        "policy gate must not be null");
                return new PolicyBefore(key, gate);
            }, machine.deadline());
            if (machine.cancelled()) {
                machine.cancel();
                return machine.result();
            }
            if (call.timeout()) return complete(machine, SerialMachine.timeoutFailure());
            if (call.failure() != null) return callbackFailure(machine, call.failure());
            frame.key = call.value().key();
            Gate gate = call.value().gate();
            if (gate instanceof Gate.Proceed) {
                frame.phase = 1;
                machine.push(control.body(), frame.entry);
            } else if (gate instanceof Gate.Reject) {
                Gate.Reject reject = (Gate.Reject) gate;
                return complete(machine, Outcome.rejected(reject.reason()));
            } else if (gate instanceof Gate.Fail) {
                Gate.Fail fail = (Gate.Fail) gate;
                return complete(machine, Outcome.failed(fail.failure()));
            } else {
                throw new IllegalStateException("Unknown Gate decision: "
                        + (gate == null ? "null" : gate.getClass().getName()));
            }
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Outcome<?> reduce(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine, Outcome<?> child) {
            Policy<Object> policy = (Policy<Object>) control.policy().instance();
            CallbackRunner.Result<Boolean> call = machine.callbacks().call(signal -> {
                policy.after(machine.context(frame, control, signal), frame.key,
                        Completion.from(child));
                return Boolean.TRUE;
            }, machine.deadline());
            if (machine.cancelled()) throw new CancellationException(
                    "flow execution was cancelled");
            if (call.timeout()) return SerialMachine.timeoutFailure();
            if (call.failure() != null) return reducerCallbackFailure(machine, call.failure());
            Map<String, String> attrs = new LinkedHashMap<String, String>();
            attrs.put("attempt", Integer.toString(frame.attempt));
            attrs.put("outcome", child.kind().name());
            machine.event(FlowObserver.Type.POLICY_AFTER, control.descriptor(), attrs);
            return child;
        }
    }

    static final class PersistentPolicyHandler implements ControlKindHandler {
        @Override
        public PlanNode.Control.Kind key() {
            return PlanNode.Control.Kind.PERSISTENT_POLICY;
        }

        @Override
        @SuppressWarnings("unchecked")
        public MachineResult enter(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine) {
            if (frame.phase == 2 || frame.phase == 3) {
                MachineResult waiting = machine.awaitWake(frame);
                if (waiting != null) return waiting;
                frame.phase = 0;
            }
            requirePhase(frame, control, 0, "PersistentPolicy");
            PersistentPolicy<Object, Object> policy =
                    (PersistentPolicy<Object, Object>) control.policy().instance();
            machine.event(FlowObserver.Type.POLICY_BEFORE, control.descriptor(),
                    Collections.singletonMap("attempt", Integer.toString(frame.attempt)));
            Object existingKey = frame.key;
            Object existingState = frame.policyState;
            CallbackRunner.Result<PersistentBefore> call = machine.callbacks().call(signal -> {
                Object key = existingKey == null
                        ? Objects.requireNonNull(control.keyProjection().apply(frame.entry),
                        "policy key must not be null") : existingKey;
                Object state = existingState == null
                        ? Objects.requireNonNull(policy.initialState(key),
                        "policy initial state must not be null") : existingState;
                PersistentPolicy.Before<Object> decision = Objects.requireNonNull(
                        policy.before(machine.context(frame, control, signal), key, state),
                        "policy before decision must not be null");
                return new PersistentBefore(key, state, decision);
            }, machine.deadline());
            if (machine.cancelled()) {
                machine.cancel();
                return machine.result();
            }
            if (call.timeout()) return complete(machine, SerialMachine.timeoutFailure());
            if (call.failure() != null) return callbackFailure(machine, call.failure());
            frame.key = call.value().key();
            frame.policyState = call.value().state();
            applyPersistentDecision(machine, frame, control, call.value().decision());
            if (frame.phase == 2) {
                MachineResult waiting = machine.awaitWake(frame);
                if (waiting != null) return waiting;
                if (machine.active()) frame.phase = 0;
            }
            return null;
        }

        private void applyPersistentDecision(SerialMachine machine, RuntimeFrame frame,
                                             PlanNode.Control control,
                                             PersistentPolicy.Before<Object> decision) {
            if (decision instanceof PersistentPolicy.Proceed) {
                PersistentPolicy.Proceed<Object> proceed = (PersistentPolicy.Proceed<Object>) decision;
                frame.policyState = proceed.state();
                frame.phase = 1;
                machine.push(control.body(), frame.entry);
            } else if (decision instanceof PersistentPolicy.WaitUntil) {
                PersistentPolicy.WaitUntil<Object> wait = (PersistentPolicy.WaitUntil<Object>) decision;
                frame.policyState = wait.state();
                frame.wake = wait.instant();
                frame.phase = 2;
                machine.waitingEvent(control, frame);
            } else if (decision instanceof PersistentPolicy.Reject) {
                PersistentPolicy.Reject<Object> reject = (PersistentPolicy.Reject<Object>) decision;
                frame.policyState = reject.state();
                machine.finish(Outcome.rejected(reject.reason()));
            } else if (decision instanceof PersistentPolicy.Fail) {
                PersistentPolicy.Fail<Object> fail = (PersistentPolicy.Fail<Object>) decision;
                frame.policyState = fail.state();
                machine.finish(Outcome.failed(fail.failure()));
            } else {
                throw new IllegalStateException("Unknown PersistentPolicy.Before decision: "
                        + (decision == null ? "null" : decision.getClass().getName()));
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Outcome<?> reduce(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine, Outcome<?> child) {
            PersistentPolicy<Object, Object> policy =
                    (PersistentPolicy<Object, Object>) control.policy().instance();
            CallbackRunner.Result<PersistentPolicy.After<Object>> call =
                    machine.callbacks().call(signal -> Objects.requireNonNull(policy.after(
                            machine.context(frame, control, signal), frame.key, frame.policyState,
                            Completion.from(child)), "policy after decision must not be null"),
                            machine.deadline());
            if (machine.cancelled()) throw new CancellationException(
                    "flow execution was cancelled");
            if (call.timeout()) return SerialMachine.timeoutFailure();
            if (call.failure() != null) return reducerCallbackFailure(machine, call.failure());
            PersistentPolicy.After<Object> decision = call.value();
            Map<String, String> attrs = new LinkedHashMap<String, String>();
            attrs.put("attempt", Integer.toString(frame.attempt));
            attrs.put("outcome", child.kind().name());
            machine.event(FlowObserver.Type.POLICY_AFTER, control.descriptor(), attrs);
            if (decision instanceof PersistentPolicy.Return) {
                PersistentPolicy.Return<Object> returning = (PersistentPolicy.Return<Object>) decision;
                frame.policyState = returning.state();
                return child;
            } else if (decision instanceof PersistentPolicy.RetryAt) {
                PersistentPolicy.RetryAt<Object> retry = (PersistentPolicy.RetryAt<Object>) decision;
                frame.policyState = retry.state();
                frame.wake = retry.instant();
                frame.attempt++;
                frame.phase = 3;
                machine.waitingEvent(control, frame);
                return null;
            } else {
                throw new IllegalStateException("Unknown PersistentPolicy.After decision: "
                        + (decision == null ? "null" : decision.getClass().getName()));
            }
        }
    }
}
