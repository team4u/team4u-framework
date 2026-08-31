package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Outcome;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Internal runtime values. None of these objects are placed in a snapshot. */
final class DurableState {
    private DurableState() { }

    abstract static class SlotRole {
        private SlotRole() { }

        static SlotRole user(String hint) { return new User(hint); }

        static final class User extends SlotRole {
            private final String hint;
            User(String hint) { this.hint = text(hint, "slot hint"); }
            String hint() { return hint; }
        }

        static final class Resumed extends SlotRole {
            private final SlotRole state;
            private final String point;
            Resumed(SlotRole state, String point) {
                this.state = Objects.requireNonNull(state, "state role must not be null");
                this.point = text(point, "resume point");
            }
            SlotRole state() { return state; }
            String point() { return point; }
        }

        static final class Recovery extends SlotRole {
            private final SlotRole input;
            Recovery(SlotRole input) {
                this.input = Objects.requireNonNull(input, "input role must not be null");
            }
            SlotRole input() { return input; }
        }
    }

    static final class MachineOutcome {
        private final Outcome<?> outcome;
        private final SlotRole acceptedRole;

        MachineOutcome(Outcome<?> outcome, SlotRole acceptedRole) {
            this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            boolean accepted = outcome instanceof Outcome.Accepted;
            if (accepted != (acceptedRole != null)) {
                throw new IllegalArgumentException(
                        "accepted outcome and slot role must be present together");
            }
            this.acceptedRole = acceptedRole;
        }

        static MachineOutcome of(Outcome<?> outcome) {
            return new MachineOutcome(outcome, null);
        }

        static MachineOutcome accepted(Object value, SlotRole role) {
            return new MachineOutcome(Outcome.accepted(value), role);
        }

        Outcome<?> outcome() { return outcome; }
        SlotRole acceptedRole() { return acceptedRole; }
    }

    static final class RuntimeFrame {
        final DurablePlanNode node;
        final Object entry;
        final SlotRole entryRole;
        Object current;
        SlotRole currentRole;
        Object key;
        Object policyState;
        int phase;
        int index;
        int attempt = 1;
        Instant wake;
        Instant deadline;
        String selected;
        boolean observerStarted;
        final ArrayList<MachineOutcome> branchOutcomes;

        RuntimeFrame(DurablePlanNode node, Object entry, SlotRole entryRole) {
            this.node = Objects.requireNonNull(node, "node must not be null");
            this.entry = Objects.requireNonNull(entry, "entry must not be null");
            this.entryRole = Objects.requireNonNull(entryRole, "entryRole must not be null");
            this.current = entry;
            this.currentRole = entryRole;
            if (node instanceof DurablePlanNode.Parallel) {
                int count = ((DurablePlanNode.Parallel) node).branches().size();
                branchOutcomes = new ArrayList<MachineOutcome>(count);
                for (int i = 0; i < count; i++) branchOutcomes.add(null);
            } else {
                branchOutcomes = new ArrayList<MachineOutcome>(0);
            }
        }
    }

    static final class MachineState {
        DurableLifecycle lifecycle = DurableLifecycle.ACTIVE;
        final ArrayList<RuntimeFrame> frames;
        final String executionId;
        MachineOutcome outcome;
        String awaitingPoint;
        Object pendingSignal;

        MachineState(DurablePlanNode root, String executionId, Object input) {
            this(root, executionId, input, SlotRole.user("input"));
        }

        MachineState(DurablePlanNode root, String executionId, Object input, SlotRole entryRole) {
            this.executionId = text(executionId, "executionId");
            Objects.requireNonNull(input, "flow input must not be null");
            Objects.requireNonNull(entryRole, "entryRole must not be null");
            frames = new ArrayList<RuntimeFrame>();
            frames.add(new RuntimeFrame(root, input, entryRole));
        }

        MachineState(String executionId, ArrayList<RuntimeFrame> frames) {
            this.executionId = text(executionId, "executionId");
            this.frames = Objects.requireNonNull(frames, "frames must not be null");
        }
    }

    static final class MachineResult {
        private final DurableLifecycle lifecycle;
        private final MachineOutcome outcome;
        private final String awaitingPoint;
        private final Instant wakeAt;

        MachineResult(DurableLifecycle lifecycle, MachineOutcome outcome,
                      String awaitingPoint, Instant wakeAt) {
            this.lifecycle = lifecycle;
            this.outcome = outcome;
            this.awaitingPoint = awaitingPoint;
            this.wakeAt = wakeAt;
        }

        DurableLifecycle lifecycle() { return lifecycle; }
        MachineOutcome outcome() { return outcome; }
        String awaitingPoint() { return awaitingPoint; }
        Instant wakeAt() { return wakeAt; }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
