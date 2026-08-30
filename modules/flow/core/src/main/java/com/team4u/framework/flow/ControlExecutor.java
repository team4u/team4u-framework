package com.team4u.framework.flow;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

/**
 * Control 节点（Timeout/Retry/Policy/PersistentPolicy）的进入逻辑：
 * 计算 deadline、压入 body、调用 Policy.before，并处理等待/恢复阶段。
 * 与 {@link FrameReducer} 配对：本类处理进入与等待，FrameReducer 处理子帧完成后的归约。
 */
final class ControlExecutor {
    private static final class PolicyBefore {
        private final Object key;
        private final Gate gate;

        public PolicyBefore(Object key, Gate gate) {
            this.key = key;
            this.gate = gate;
        }

        public Object key() {
            return key;
        }

        public Gate gate() {
            return gate;
        }
    }

    private static final class PersistentBefore {
        private final Object key;
        private final Object state;
        private final PersistentPolicy.Before<Object> decision;

        public PersistentBefore(Object key, Object state, PersistentPolicy.Before<Object> decision) {
            this.key = key;
            this.state = state;
            this.decision = decision;
        }

        public Object key() {
            return key;
        }

        public Object state() {
            return state;
        }

        public PersistentPolicy.Before<Object> decision() {
            return decision;
        }
    }

    private ControlExecutor() { }

    /** 按类型分发到 Timeout/Retry/Policy/PersistentPolicy 的进入处理。 */
    static MachineResult enter(SerialMachine machine, RuntimeFrame frame,
                               PlanNode.Control control) {
        switch (control.kind()) {
            case TIMEOUT:
                return timeout(machine, frame, control);
            case RETRY:
                return retry(machine, frame, control);
            case POLICY:
                return policy(machine, frame, control);
            case PERSISTENT_POLICY:
                return persistent(machine, frame, control);
            default:
                throw new IllegalStateException("Unknown control kind: " + control.kind());
        }
    }

    /** Timeout 进入：计算 deadline 并压入 body，归约时检查是否超时。 */
    private static MachineResult timeout(SerialMachine machine, RuntimeFrame frame,
                                         PlanNode.Control control) {
        requirePhase(frame, control, 0, "Timeout");
        frame.deadline = Instant.now().plus((Duration) control.configuration());
        frame.phase = 1;
        machine.push(control.body(), frame.entry);
        return null;
    }

    /**
     * Retry 进入：首次直接压入 body；等待阶段（phase 2）阻塞到 backoff 唤醒后再压入 body 重试。
     */
    private static MachineResult retry(SerialMachine machine, RuntimeFrame frame,
                                       PlanNode.Control control) {
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

    /**
     * 一次性 Policy 进入：在 CallbackRunner 中调用 before 得到策略键与 Gate。
     * Proceed 压入 body；Reject/Fail 直接终出对应 Outcome。超时与取消转为对应终态。
     */
    @SuppressWarnings("unchecked")
    private static MachineResult policy(SerialMachine machine, RuntimeFrame frame,
                                        PlanNode.Control control) {
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

    /**
     * PersistentPolicy 进入：等待阶段（phase 2/3）先 awaitWake；否则调用 before 取得决策。
     * Proceed 压入 body；WaitUntil 进入等待；Reject/Fail 直接 finish。
     */
    @SuppressWarnings("unchecked")
    private static MachineResult persistent(SerialMachine machine, RuntimeFrame frame,
                                            PlanNode.Control control) {
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

    /** 应用 PersistentPolicy.Before 决策：更新状态并进入对应阶段或直接 finish。 */
    private static void applyPersistentDecision(SerialMachine machine, RuntimeFrame frame,
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

    private static MachineResult callbackFailure(SerialMachine machine, Throwable failure) {
        if (machine.cancelled()) {
            machine.cancel();
            return machine.result();
        }
        return complete(machine, machine.policyFailure(failure));
    }

    private static MachineResult complete(SerialMachine machine, Outcome<?> outcome) {
        machine.finish(outcome);
        return null;
    }

    private static void requirePhase(RuntimeFrame frame, PlanNode.Control control,
                                     int expected, String kind) {
        if (frame.phase != expected) throw new IllegalStateException(kind
                + " child frame is missing at " + control.descriptor().path());
    }
}
