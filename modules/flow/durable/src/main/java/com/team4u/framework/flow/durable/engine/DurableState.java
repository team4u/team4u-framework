package com.team4u.framework.flow.durable.engine;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.model.Resumed;
import com.team4u.framework.flow.model.Outcome;

/**
 * 持久化流引擎内部状态模型族（Durable Internal State Models）。
 *
 * <p>封装 Durable 运行时在执行期间的瞬态对象，包括插槽角色路径（{@link SlotRole}）、
 * 携带角色追踪的执行结果（{@link MachineOutcome}）、执行栈帧（{@link RuntimeFrame}）、
 * 状态机整体状态（{@link MachineState}）以及单次推进结果（{@link MachineResult}）。</p>
 *
 * @author jay.wu
 */
public final class DurableState {
    private DurableState() { }

    /**
     * 插槽角色（SlotRole）抽象基类，用于在持久化快照中构建确定性的插槽键（Slot Key）。
     */
    public static abstract class SlotRole {
        private SlotRole() { }

        public static SlotRole user(String hint) { return new User(hint); }

        /** 用户业务数据插槽。 */
        @lombok.Getter
        @lombok.experimental.Accessors(fluent = true)
        public static final class User extends SlotRole {
            private final String hint;
            public User(String hint) { this.hint = text(hint, "slot hint"); }
        }

        /** 异步恢复信号插槽包装。 */
        @lombok.Getter
        @lombok.experimental.Accessors(fluent = true)
        public static final class Resumed extends SlotRole {
            private final SlotRole state;
            private final String point;
            public Resumed(SlotRole state, String point) {
                this.state = Objects.requireNonNull(state, "state role must not be null");
                this.point = text(point, "resume point");
            }
        }

        /** 降级恢复上下文插槽包装。 */
        @lombok.Getter
        @lombok.experimental.Accessors(fluent = true)
        public static final class Recovery extends SlotRole {
            private final SlotRole input;
            public Recovery(SlotRole input) {
                this.input = Objects.requireNonNull(input, "input role must not be null");
            }
        }
    }


    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    public static final class MachineOutcome {
        private final Outcome<?> outcome;
        private final SlotRole acceptedRole;

        public MachineOutcome(Outcome<?> outcome, SlotRole acceptedRole) {
            this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            boolean accepted = outcome instanceof Outcome.Accepted;
            if (accepted != (acceptedRole != null)) {
                throw new IllegalArgumentException(
                        "accepted outcome and slot role must be present together");
            }
            this.acceptedRole = acceptedRole;
        }

        public static MachineOutcome of(Outcome<?> outcome) {
            return new MachineOutcome(outcome, null);
        }

        public static MachineOutcome accepted(Object value, SlotRole role) {
            return new MachineOutcome(Outcome.accepted(value), role);
        }
    }

    public static final class RuntimeFrame {
        public final DurablePlanNode node;
        public final Object entry;
        public final SlotRole entryRole;
        public Object current;
        public SlotRole currentRole;
        public Object key;
        public Object policyState;
        public int phase;
        public int index;
        public int attempt = 1;
        public Instant wake;
        public Instant deadline;
        public String selected;
        public boolean observerStarted;
        public final ArrayList<MachineOutcome> branchOutcomes;

        public RuntimeFrame(DurablePlanNode node, Object entry, SlotRole entryRole) {
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

    public static final class MachineState {
        public DurableLifecycle lifecycle = DurableLifecycle.ACTIVE;
        public final ArrayList<RuntimeFrame> frames;
        public final String executionId;
        public MachineOutcome outcome;
        public String awaitingPoint;
        public Object pendingSignal;

        public MachineState(DurablePlanNode root, String executionId, Object input) {
            this(root, executionId, input, SlotRole.user("input"));
        }

        public MachineState(DurablePlanNode root, String executionId, Object input, SlotRole entryRole) {
            this.executionId = text(executionId, "executionId");
            Objects.requireNonNull(input, "flow input must not be null");
            Objects.requireNonNull(entryRole, "entryRole must not be null");
            frames = new ArrayList<RuntimeFrame>();
            frames.add(new RuntimeFrame(root, input, entryRole));
        }

        public MachineState(String executionId, ArrayList<RuntimeFrame> frames) {
            this.executionId = text(executionId, "executionId");
            this.frames = Objects.requireNonNull(frames, "frames must not be null");
        }
    }

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    public static final class MachineResult {
        private final DurableLifecycle lifecycle;
        private final MachineOutcome outcome;
        private final String awaitingPoint;
        private final Instant wakeAt;

        public MachineResult(DurableLifecycle lifecycle, MachineOutcome outcome,
                      String awaitingPoint, Instant wakeAt) {
            this.lifecycle = lifecycle;
            this.outcome = outcome;
            this.awaitingPoint = awaitingPoint;
            this.wakeAt = wakeAt;
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
