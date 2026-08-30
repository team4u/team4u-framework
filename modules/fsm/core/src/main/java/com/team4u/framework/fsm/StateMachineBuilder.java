package com.team4u.framework.fsm;

import com.team4u.framework.fsm.exception.StateMachineDefinitionException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link StateMachine} 的强类型构建器。
 * <p>
 * 典型用法（条件分支需显式声明为多条带守卫的边）：
 * <pre>{@code
 * StateMachine<OrderState, OrderEvent, Order> machine = StateMachine
 *         .<OrderState, OrderEvent, Order>builder("order", OrderState.CREATED)
 *         .from(OrderState.CREATED).on(OrderEvent.SUBMIT).to(OrderState.SUBMITTED)
 *             .named("created-submit").action(ctx -&gt; audit(ctx))
 *         .fromAny().on(OrderEvent.CANCEL).when("not shipped", ctx -&gt; !shipped(ctx))
 *             .to(OrderState.CANCELLED)
 *         .build();
 * }</pre>
 * <p>
 * 构建器仅负责定义迁移，本身非线程安全，应在启动期单线程使用；
 * {@link #build()} 产出的状态机与迁移集合均不可变，可并发复用。
 *
 * @param <S> 状态类型
 * @param <E> 事件类型
 * @param <C> 业务上下文类型
 * @author jay.wu
 */
public final class StateMachineBuilder<S, E, C> {

    private final String machineId;
    private final S initialState;
    private final List<TransitionDraft> drafts = new ArrayList<>();
    private boolean built;
    private int incompleteTransitionCount;

    StateMachineBuilder(String machineId, S initialState) {
        this.machineId = requireText(machineId, "State machine id cannot be empty");
        this.initialState = requireValue(initialState, "Initial state cannot be null");
    }

    /**
     * 从一个精确状态开始定义迁移。
     *
     * @param state 来源状态，非空
     * @return 来源配置阶段
     */
    public SourceStage from(S state) {
        ensureOpen();
        return new SourceStage(false, requireValue(state, "Transition source state cannot be null"));
    }

    /**
     * 从任意状态开始定义全局迁移。此类规则不参与“精确状态 + 精确事件”最高优先层：
     * 与精确来源的通配事件规则（{@code from(state).onAny()}）同属单边通配层，
     * 两桶按声明顺序归并评估；任意来源 + 任意事件规则则作为最后的全局兜底。
     *
     * @return 来源配置阶段
     */
    public SourceStage fromAny() {
        ensureOpen();
        return new SourceStage(true, null);
    }

    /**
     * 校验全部规则并构建不可变状态机。
     * <p>
     * 校验包括：规则必须完整（已声明 {@code on}/{@code onAny} 的规则必须指定目标）、
     * 至少包含一条迁移、迁移标识不得重复、同一来源与事件组合（同一匹配桶）内不得出现
     * 位于无条件规则之后的其他规则（它们在该桶内永远不可达）。
     * <p>
     * 注意：跨桶的部分遮蔽（例如 {@code from(A).onAny()} 无条件规则先于
     * {@code fromAny().on(B)} 声明）不会被拒绝——后者在其他状态下仍可命中，
     * 这是允许的遮蔽语义；单边通配桶之间按声明顺序归并，有意重叠是合法的。
     *
     * @return 不可变状态机
     * @throws StateMachineDefinitionException 定义不合法时抛出
     */
    public StateMachine<S, E, C> build() {
        ensureOpen();
        if (incompleteTransitionCount > 0) {
            throw definitionError("State machine contains " + incompleteTransitionCount
                    + " incomplete transition definition(s)");
        }
        if (drafts.isEmpty()) {
            throw definitionError("State machine must contain at least one transition");
        }

        validateFallbackOrder();
        List<Transition<S, E, C>> transitions = materializeTransitions();
        built = true;
        return new StateMachine<>(machineId, initialState, transitions);
    }

    private List<Transition<S, E, C>> materializeTransitions() {
        Set<String> usedIds = new HashSet<>();
        for (TransitionDraft draft : drafts) {
            if (draft.id != null && !usedIds.add(draft.id)) {
                throw definitionError("Duplicate transition id: " + draft.id);
            }
        }

        List<Transition<S, E, C>> transitions = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            TransitionDraft draft = drafts.get(i);
            String id = draft.id;
            if (id == null) {
                id = nextGeneratedId(i + 1, usedIds);
            }
            transitions.add(new Transition<>(
                    id,
                    draft.from,
                    draft.event,
                    draft.to,
                    draft.anySource,
                    draft.anyEvent,
                    draft.stay,
                    draft.guardDescription,
                    draft.guard,
                    draft.action,
                    i
            ));
        }
        return transitions;
    }

    private String nextGeneratedId(int ordinal, Set<String> usedIds) {
        String base = "transition-" + ordinal;
        String candidate = base;
        int suffix = 2;
        while (!usedIds.add(candidate)) {
            candidate = base + '-' + suffix++;
        }
        return candidate;
    }

    private void validateFallbackOrder() {
        Map<RouteKey, TransitionDraft> unconditionalByRoute = new HashMap<>();
        Map<RouteKey, Integer> fallbackPositions = new HashMap<>();
        for (int i = 0; i < drafts.size(); i++) {
            TransitionDraft draft = drafts.get(i);
            RouteKey route = new RouteKey(draft.anySource, draft.from, draft.anyEvent, draft.event);
            TransitionDraft fallback = unconditionalByRoute.get(route);
            if (fallback != null) {
                throw definitionError("Unreachable transition after unconditional fallback: "
                        + describe(draft, i) + " (fallback: "
                        + describe(fallback, fallbackPositions.get(route)) + ')');
            }
            if (draft.guard == null) {
                unconditionalByRoute.put(route, draft);
                fallbackPositions.put(route, i);
            }
        }
    }

    private String describe(TransitionDraft draft, int position) {
        if (draft.id != null) {
            return draft.id;
        }
        return "#" + position + ' '
                + (draft.anySource ? "*" : String.valueOf(draft.from))
                + " --(" + (draft.anyEvent ? "*" : String.valueOf(draft.event)) + ")--> "
                + (draft.stay ? "=" : String.valueOf(draft.to));
    }

    private TransitionDraft complete(EventStage stage, S target, boolean stay) {
        ensureOpen();
        stage.ensureIncomplete();
        TransitionDraft draft = new TransitionDraft(
                stage.anySource,
                stage.from,
                stage.anyEvent,
                stage.event,
                target,
                stay,
                stage.guardDescription,
                stage.guard
        );
        drafts.add(draft);
        stage.completed = true;
        incompleteTransitionCount--;
        return draft;
    }

    private void ensureOpen() {
        if (built) {
            throw definitionError("State machine builder has already been built");
        }
    }

    private StateMachineDefinitionException definitionError(String message) {
        return new StateMachineDefinitionException(message + "|machineId=" + machineId);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new StateMachineDefinitionException(message);
        }
        return value.trim();
    }

    private static <T> T requireValue(T value, String message) {
        if (value == null) {
            throw new StateMachineDefinitionException(message);
        }
        return value;
    }

    /**
     * 来源状态配置阶段。
     */
    public final class SourceStage {
        private final boolean anySource;
        private final S from;

        private SourceStage(boolean anySource, S from) {
            this.anySource = anySource;
            this.from = from;
        }

        /**
         * 指定精确触发事件。
         *
         * @param event 触发事件，非空
         * @return 事件配置阶段
         */
        public EventStage on(E event) {
            ensureOpen();
            requireValue(event, "Transition event cannot be null");
            incompleteTransitionCount++;
            return new EventStage(anySource, from, false, event);
        }

        /**
         * 匹配任意非空事件。通配事件规则不参与“精确状态 + 精确事件”最高优先层：
         * 精确来源的 {@code onAny()} 与任意来源的 {@code on()} 同属单边通配层，
         * 两桶按声明顺序归并评估；任意来源 + 任意事件规则则作为最后的全局兜底，
         * 通常应配合守卫使用。
         *
         * @return 事件配置阶段
         */
        public EventStage onAny() {
            ensureOpen();
            incompleteTransitionCount++;
            return new EventStage(anySource, from, true, null);
        }
    }

    /**
     * 事件与守卫配置阶段。完成迁移（{@code to}/{@code stay}）之后不可再追加守卫。
     */
    public final class EventStage {
        private final boolean anySource;
        private final S from;
        private final boolean anyEvent;
        private final E event;
        private TransitionGuard<S, E, C> guard;
        private String guardDescription;
        private boolean completed;

        private EventStage(boolean anySource, S from, boolean anyEvent, E event) {
            this.anySource = anySource;
            this.from = from;
            this.anyEvent = anyEvent;
            this.event = event;
        }

        /**
         * 添加一个守卫。多次调用时按声明顺序进行短路与运算。
         *
         * @param nextGuard 守卫，非空
         * @return 当前配置阶段
         */
        public EventStage when(TransitionGuard<S, E, C> nextGuard) {
            return when("guard", nextGuard);
        }

        /**
         * 添加带可读描述的守卫。多次调用时按声明顺序进行短路与运算，
         * 描述会按声明顺序组合，用于诊断与状态图输出。
         *
         * @param description 守卫描述，非空
         * @param nextGuard   守卫，非空
         * @return 当前配置阶段
         */
        public EventStage when(String description, TransitionGuard<S, E, C> nextGuard) {
            ensureOpen();
            ensureIncomplete();
            requireValue(nextGuard, "Transition guard cannot be null");
            String normalizedDescription = requireText(description, "Guard description cannot be empty");
            guard = guard == null ? nextGuard : guard.and(nextGuard);
            guardDescription = guardDescription == null
                    ? normalizedDescription
                    : '(' + guardDescription + ") && (" + normalizedDescription + ')';
            return this;
        }

        /**
         * 指定固定目标状态并完成当前规则。
         *
         * @param state 目标状态，非空
         * @return 已完成迁移配置
         */
        public ConfiguredTransition to(S state) {
            return new ConfiguredTransition(complete(this,
                    requireValue(state, "Transition target state cannot be null"), false));
        }

        /**
         * 完成一条保持当前状态的迁移，动作仍会正常执行。
         *
         * @return 已完成迁移配置
         */
        public ConfiguredTransition stay() {
            return new ConfiguredTransition(complete(this, null, true));
        }

        private void ensureIncomplete() {
            if (completed) {
                throw definitionError("Transition definition has already been completed");
            }
        }
    }

    /**
     * 已完成迁移的可选元数据与动作配置阶段。
     * <p>
     * 该阶段同时支持链式开始下一条规则（{@link #from}/{@link #fromAny}）
     * 或直接 {@link #build()}，因此整个状态机定义可以作为一个连续的流式声明。
     */
    public final class ConfiguredTransition {
        private final TransitionDraft draft;

        private ConfiguredTransition(TransitionDraft draft) {
            this.draft = draft;
        }

        /**
         * 链式开始下一条迁移规则，等价于在构建器上直接调用 {@code from(state)}。
         *
         * @param state 来源状态，非空
         * @return 新规则的来源配置阶段
         */
        public SourceStage from(S state) {
            return StateMachineBuilder.this.from(state);
        }

        /**
         * 链式开始下一条全局迁移规则，等价于在构建器上直接调用 {@code fromAny()}。
         *
         * @return 新规则的来源配置阶段
         */
        public SourceStage fromAny() {
            return StateMachineBuilder.this.fromAny();
        }

        /**
         * 校验全部规则并构建不可变状态机，等价于在构建器上直接调用 {@code build()}。
         *
         * @return 不可变状态机
         * @throws StateMachineDefinitionException 定义不合法时抛出
         */
        public StateMachine<S, E, C> build() {
            return StateMachineBuilder.this.build();
        }

        /**
         * 设置稳定的迁移标识，用于审计与诊断。未显式命名时将生成
         * {@code transition-<声明序号>} 形式的标识。每条规则只能命名一次，
         * 重复命名会抛出定义异常。
         *
         * @param id 迁移标识，非空且在当前状态机内唯一
         * @return 当前配置阶段
         * @throws StateMachineDefinitionException 标识为空或重复命名时抛出
         */
        public ConfiguredTransition named(String id) {
            ensureOpen();
            if (draft.id != null) {
                throw definitionError("Transition has already been named: " + draft.id);
            }
            draft.id = requireText(id, "Transition id cannot be empty");
            return this;
        }

        /**
         * 追加迁移动作。多次调用时按声明顺序执行；任一动作抛出异常即终止后续动作，
         * 且本次迁移视为失败。
         *
         * @param nextAction 动作，非空
         * @return 当前配置阶段
         */
        public ConfiguredTransition action(TransitionAction<S, E, C> nextAction) {
            ensureOpen();
            requireValue(nextAction, "Transition action cannot be null");
            draft.action = draft.action == null ? nextAction : draft.action.andThen(nextAction);
            return this;
        }
    }

    private final class TransitionDraft {
        private final boolean anySource;
        private final S from;
        private final boolean anyEvent;
        private final E event;
        private final S to;
        private final boolean stay;
        private final String guardDescription;
        private final TransitionGuard<S, E, C> guard;
        private String id;
        private TransitionAction<S, E, C> action;

        private TransitionDraft(boolean anySource,
                                S from,
                                boolean anyEvent,
                                E event,
                                S to,
                                boolean stay,
                                String guardDescription,
                                TransitionGuard<S, E, C> guard) {
            this.anySource = anySource;
            this.from = from;
            this.anyEvent = anyEvent;
            this.event = event;
            this.to = to;
            this.stay = stay;
            this.guardDescription = guardDescription;
            this.guard = guard;
        }
    }

    private static final class RouteKey {
        private final boolean anySource;
        private final Object from;
        private final boolean anyEvent;
        private final Object event;

        private RouteKey(boolean anySource, Object from, boolean anyEvent, Object event) {
            this.anySource = anySource;
            this.from = from;
            this.anyEvent = anyEvent;
            this.event = event;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RouteKey)) {
                return false;
            }
            RouteKey that = (RouteKey) other;
            return anySource == that.anySource
                    && anyEvent == that.anyEvent
                    && Objects.equals(from, that.from)
                    && Objects.equals(event, that.event);
        }

        @Override
        public int hashCode() {
            return Objects.hash(anySource, from, anyEvent, event);
        }
    }
}
