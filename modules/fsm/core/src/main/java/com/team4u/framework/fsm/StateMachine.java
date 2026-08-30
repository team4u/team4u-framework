package com.team4u.framework.fsm;

import com.team4u.framework.fsm.exception.TransitionExecutionException;
import com.team4u.framework.fsm.exception.TransitionRejectedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不可变、无状态的有限状态机定义。
 * <p>
 * 状态机不保存业务对象的当前状态：调用方传入当前状态、事件与业务上下文，
 * 引擎判定并执行迁移后返回完整结果，由调用方决定如何更新或持久化状态。
 * 因此同一实例可被多线程并发复用，也不会与数据库事务、容器生命周期产生耦合。
 * 实例本身不可变且线程安全；但由此，守卫与动作的实现可能在多线程中被并发调用，
 * 它们必须自行保证线程安全。
 * <p>
 * 迁移匹配按“具体度”分层，层间顺序固定，层内按声明顺序取第一个守卫通过的规则：
 * <ol>
 *     <li>精确状态 + 精确事件；</li>
 *     <li>单边通配（精确状态 + 任意事件、任意状态 + 精确事件）两桶具体度相同，
 *         按声明顺序归并；两桶之间允许有意重叠与局部遮蔽，这是预期的语义；
 *         但同一桶内位于无条件规则之后的规则会在构建期被拒绝；</li>
 *     <li>任意状态 + 任意事件，作为最后的全局兜底。</li>
 * </ol>
 * 精确规则永远不会被更早声明的通配规则遮蔽（跨层遮蔽只发生在更具体的层未命中时，
 * 这是设计允许的部分遮蔽，构建期不会拒绝）。守卫或动作抛出异常时引擎快速失败，
 * 不会再尝试任何后续候选规则。使用 {@link #fire} 在无可用迁移时抛出异常，
 * 或使用 {@link #tryFire} 获取语义化结果；需要状态图文本时可使用
 * {@link StateMachineMermaid#render(StateMachine)}。
 *
 * @param <S> 状态类型，推荐使用枚举或正确实现 equals/hashCode 的不可变值对象
 * @param <E> 事件类型，推荐使用枚举或正确实现 equals/hashCode 的不可变值对象
 * @param <C> 业务上下文类型
 * @author jay.wu
 */
public final class StateMachine<S, E, C> {

    private final String id;
    private final S initialState;
    private final List<Transition<S, E, C>> transitions;
    private final Set<S> states;
    private final Set<E> events;
    private final Map<S, Map<E, List<Transition<S, E, C>>>> exactStateExactEvent;
    private final Map<S, List<Transition<S, E, C>>> exactStateAnyEvent;
    private final Map<E, List<Transition<S, E, C>>> anyStateExactEvent;
    private final List<Transition<S, E, C>> anyStateAnyEvent;

    StateMachine(String id, S initialState, List<Transition<S, E, C>> sourceTransitions) {
        this.id = id;
        this.initialState = initialState;
        this.transitions = Collections.unmodifiableList(new ArrayList<>(sourceTransitions));

        LinkedHashSet<S> stateSet = new LinkedHashSet<>();
        LinkedHashSet<E> eventSet = new LinkedHashSet<>();
        stateSet.add(initialState);

        Map<S, Map<E, List<Transition<S, E, C>>>> exactExact = new LinkedHashMap<>();
        Map<S, List<Transition<S, E, C>>> exactAny = new LinkedHashMap<>();
        Map<E, List<Transition<S, E, C>>> anyExact = new LinkedHashMap<>();
        List<Transition<S, E, C>> anyAny = new ArrayList<>();

        for (Transition<S, E, C> transition : sourceTransitions) {
            if (!transition.isAnySource()) {
                stateSet.add(transition.getFrom());
            }
            if (!transition.isAnyEvent()) {
                eventSet.add(transition.getEvent());
            }
            if (!transition.isStay()) {
                stateSet.add(transition.getTo());
            }
            index(transition, exactExact, exactAny, anyExact, anyAny);
        }

        this.states = Collections.unmodifiableSet(stateSet);
        this.events = Collections.unmodifiableSet(eventSet);
        this.exactStateExactEvent = freezeNestedIndex(exactExact);
        this.exactStateAnyEvent = freezeIndex(exactAny);
        this.anyStateExactEvent = freezeIndex(anyExact);
        this.anyStateAnyEvent = Collections.unmodifiableList(anyAny);
    }

    /**
     * 创建状态机构建器。
     * <p>
     * 构建器仅应在启动期使用，产出的状态机实例可长期复用。
     *
     * @param id           状态机标识，非空
     * @param initialState 初始状态，非空
     * @param <S>          状态类型
     * @param <E>          事件类型
     * @param <C>          业务上下文类型
     * @return 状态机构建器
     */
    public static <S, E, C> StateMachineBuilder<S, E, C> builder(String id, S initialState) {
        return new StateMachineBuilder<>(id, initialState);
    }

    /**
     * 严格触发一次迁移。没有候选迁移（{@link TransitionOutcome#NO_TRANSITION}）
     * 或所有守卫均拒绝（{@link TransitionOutcome#GUARD_REJECTED}）时抛出
     * {@link TransitionRejectedException}；守卫或动作执行失败时抛出
     * {@link TransitionExecutionException}。
     *
     * @param state   当前状态，非空
     * @param event   触发事件，非空
     * @param context 业务上下文，允许为 {@code null}
     * @return 成功的迁移结果
     */
    public TransitionResult<S, E, C> fire(S state, E event, C context) {
        TransitionResult<S, E, C> result = tryFire(state, event, context);
        if (result.isRejected()) {
            throw new TransitionRejectedException(result);
        }
        return result;
    }

    /**
     * 使用空业务上下文严格触发一次迁移。
     *
     * @param state 当前状态，非空
     * @param event 触发事件，非空
     * @return 成功的迁移结果
     */
    public TransitionResult<S, E, C> fire(S state, E event) {
        return fire(state, event, null);
    }

    /**
     * 尝试触发一次迁移。未命中候选迁移或守卫全部拒绝时返回对应的语义化结果，
     * 不抛出拒绝异常；守卫或动作自身执行失败仍会抛出 {@link TransitionExecutionException}。
     *
     * @param state   当前状态，非空
     * @param event   触发事件，非空
     * @param context 业务上下文，允许为 {@code null}
     * @return 迁移结果，结果本身不会为 {@code null}
     */
    public TransitionResult<S, E, C> tryFire(S state, E event, C context) {
        requireInput(state, "Current state cannot be null");
        requireInput(event, "Transition event cannot be null");

        Resolution<S, E, C> resolution = resolve(state, event, context);
        if (resolution.transition == null) {
            TransitionOutcome outcome = resolution.evaluatedCount == 0
                    ? TransitionOutcome.NO_TRANSITION
                    : TransitionOutcome.GUARD_REJECTED;
            return TransitionResult.rejected(id, outcome, state, event, context,
                    resolution.evaluatedCount);
        }

        try {
            resolution.transition.execute(resolution.context);
        } catch (Exception exception) {
            throw new TransitionExecutionException(resolution.context,
                    TransitionExecutionException.Phase.ACTION, exception);
        }

        return TransitionResult.transitioned(id, state, event, resolution.context.getTo(), context,
                resolution.transition, resolution.evaluatedCount);
    }

    /**
     * 使用空业务上下文尝试触发一次迁移。
     *
     * @param state 当前状态，非空
     * @param event 触发事件，非空
     * @return 迁移结果，结果本身不会为 {@code null}
     */
    public TransitionResult<S, E, C> tryFire(S state, E event) {
        return tryFire(state, event, null);
    }

    /**
     * 严格要求迁移并仅返回目标状态。无可用迁移时抛出 {@link TransitionRejectedException}。
     *
     * @param state   当前状态，非空
     * @param event   触发事件，非空
     * @param context 业务上下文，允许为 {@code null}
     * @return 目标状态
     */
    public S nextState(S state, E event, C context) {
        return fire(state, event, context).getTo();
    }

    /**
     * 使用空业务上下文严格执行迁移并仅返回目标状态。
     *
     * @param state 当前状态，非空
     * @param event 触发事件，非空
     * @return 目标状态
     */
    public S nextState(S state, E event) {
        return nextState(state, event, null);
    }

    /**
     * 判断一个状态是否没有任何可能的出边。
     * <p>
     * 该判断不执行守卫；只要存在任意全局迁移（任意来源），所有状态都视为可继续迁移。
     *
     * @param state 状态，非空
     * @return 没有任何出边时返回 {@code true}
     */
    public boolean isTerminal(S state) {
        requireInput(state, "State cannot be null");
        Map<E, List<Transition<S, E, C>>> exactEvents = exactStateExactEvent.get(state);
        return (exactEvents == null || exactEvents.isEmpty())
                && !exactStateAnyEvent.containsKey(state)
                && anyStateExactEvent.isEmpty()
                && anyStateAnyEvent.isEmpty();
    }

    /**
     * 获取状态机标识。
     *
     * @return 状态机标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取初始状态。
     *
     * @return 初始状态
     */
    public S getInitialState() {
        return initialState;
    }

    /**
     * 按声明顺序获取全部迁移定义，返回集合不可修改。
     *
     * @return 迁移定义列表
     */
    public List<Transition<S, E, C>> getTransitions() {
        return transitions;
    }

    /**
     * 获取定义中出现过或可达的全部状态（含初始状态与全部目标状态）。
     *
     * @return 状态集合，不可修改
     */
    public Set<S> getStates() {
        return states;
    }

    /**
     * 获取被精确引用过的全部事件。通配事件的迁移不贡献事件。
     *
     * @return 事件集合，不可修改
     */
    public Set<E> getEvents() {
        return events;
    }

    @Override
    public String toString() {
        return "StateMachine{id='" + id + "', initialState=" + initialState
                + ", transitions=" + transitions.size() + '}';
    }

    private Resolution<S, E, C> resolve(S state, E event, C context) {
        Resolution<S, E, C> resolution = new Resolution<>();

        Map<E, List<Transition<S, E, C>>> byEvent = exactStateExactEvent.get(state);
        if (evaluateList(byEvent == null ? null : byEvent.get(event), state, event, context, resolution)) {
            return resolution;
        }

        List<Transition<S, E, C>> exactSource = exactStateAnyEvent.get(state);
        List<Transition<S, E, C>> exactEvent = anyStateExactEvent.get(event);
        if (evaluateMerged(exactSource, exactEvent, state, event, context, resolution)) {
            return resolution;
        }

        evaluateList(anyStateAnyEvent, state, event, context, resolution);
        return resolution;
    }

    private boolean evaluateMerged(List<Transition<S, E, C>> first,
                                   List<Transition<S, E, C>> second,
                                   S state,
                                   E event,
                                   C context,
                                   Resolution<S, E, C> resolution) {
        int firstIndex = 0;
        int secondIndex = 0;
        int firstSize = first == null ? 0 : first.size();
        int secondSize = second == null ? 0 : second.size();

        while (firstIndex < firstSize || secondIndex < secondSize) {
            Transition<S, E, C> transition;
            if (secondIndex >= secondSize || (firstIndex < firstSize
                    && first.get(firstIndex).getDeclarationOrder()
                    < second.get(secondIndex).getDeclarationOrder())) {
                transition = first.get(firstIndex++);
            } else {
                transition = second.get(secondIndex++);
            }
            if (evaluate(transition, state, event, context, resolution)) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateList(List<Transition<S, E, C>> candidates,
                                 S state,
                                 E event,
                                 C context,
                                 Resolution<S, E, C> resolution) {
        if (candidates == null) {
            return false;
        }
        for (Transition<S, E, C> transition : candidates) {
            if (evaluate(transition, state, event, context, resolution)) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluate(Transition<S, E, C> transition,
                             S state,
                             E event,
                             C context,
                             Resolution<S, E, C> resolution) {
        resolution.evaluatedCount++;
        S target = transition.resolveTarget(state);
        TransitionContext<S, E, C> transitionContext = new TransitionContext<>(
                id, transition.getId(), state, event, target, context);
        try {
            if (!transition.isAllowed(transitionContext)) {
                return false;
            }
        } catch (Exception exception) {
            throw new TransitionExecutionException(transitionContext,
                    TransitionExecutionException.Phase.GUARD, exception);
        }
        resolution.transition = transition;
        resolution.context = transitionContext;
        return true;
    }

    private void index(Transition<S, E, C> transition,
                       Map<S, Map<E, List<Transition<S, E, C>>>> exactExact,
                       Map<S, List<Transition<S, E, C>>> exactAny,
                       Map<E, List<Transition<S, E, C>>> anyExact,
                       List<Transition<S, E, C>> anyAny) {
        if (!transition.isAnySource() && !transition.isAnyEvent()) {
            Map<E, List<Transition<S, E, C>>> byEvent = exactExact.get(transition.getFrom());
            if (byEvent == null) {
                byEvent = new LinkedHashMap<>();
                exactExact.put(transition.getFrom(), byEvent);
            }
            add(byEvent, transition.getEvent(), transition);
        } else if (!transition.isAnySource()) {
            add(exactAny, transition.getFrom(), transition);
        } else if (!transition.isAnyEvent()) {
            add(anyExact, transition.getEvent(), transition);
        } else {
            anyAny.add(transition);
        }
    }

    private static <K, S, E, C> void add(Map<K, List<Transition<S, E, C>>> index,
                                         K key,
                                         Transition<S, E, C> transition) {
        List<Transition<S, E, C>> values = index.get(key);
        if (values == null) {
            values = new ArrayList<>();
            index.put(key, values);
        }
        values.add(transition);
    }

    private static <K, S, E, C> Map<K, List<Transition<S, E, C>>> freezeIndex(
            Map<K, List<Transition<S, E, C>>> source) {
        Map<K, List<Transition<S, E, C>>> frozen = new LinkedHashMap<>();
        for (Map.Entry<K, List<Transition<S, E, C>>> entry : source.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(frozen);
    }

    private static <S, E, C> Map<S, Map<E, List<Transition<S, E, C>>>> freezeNestedIndex(
            Map<S, Map<E, List<Transition<S, E, C>>>> source) {
        Map<S, Map<E, List<Transition<S, E, C>>>> frozen = new LinkedHashMap<>();
        for (Map.Entry<S, Map<E, List<Transition<S, E, C>>>> entry : source.entrySet()) {
            frozen.put(entry.getKey(), freezeIndex(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    private static void requireInput(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class Resolution<S, E, C> {
        private Transition<S, E, C> transition;
        private TransitionContext<S, E, C> context;
        private int evaluatedCount;
    }
}
