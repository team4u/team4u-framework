package com.team4u.framework.fsm;

/**
 * 不可变的状态迁移定义。
 * <p>
 * 目标状态只能是构建期固定的状态或“保持当前状态”，动作无法改写目标状态；
 * 需要按条件决定去向时，必须显式声明多条带守卫的迁移。
 *
 * @param <S> 状态类型
 * @param <E> 事件类型
 * @param <C> 业务上下文类型
 * @author jay.wu
 */
public final class Transition<S, E, C> {

    private final String id;
    private final S from;
    private final E event;
    private final S to;
    private final boolean anySource;
    private final boolean anyEvent;
    private final boolean stay;
    private final String guardDescription;
    private final TransitionGuard<S, E, C> guard;
    private final TransitionAction<S, E, C> action;
    private final int declarationOrder;

    Transition(String id,
               S from,
               E event,
               S to,
               boolean anySource,
               boolean anyEvent,
               boolean stay,
               String guardDescription,
               TransitionGuard<S, E, C> guard,
               TransitionAction<S, E, C> action,
               int declarationOrder) {
        this.id = id;
        this.from = from;
        this.event = event;
        this.to = to;
        this.anySource = anySource;
        this.anyEvent = anyEvent;
        this.stay = stay;
        this.guardDescription = guardDescription;
        this.guard = guard;
        this.action = action;
        this.declarationOrder = declarationOrder;
    }

    /**
     * 获取迁移标识。
     *
     * @return 迁移标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取来源状态。任意来源迁移返回 {@code null}，请同时检查 {@link #isAnySource()}。
     *
     * @return 来源状态
     */
    public S getFrom() {
        return from;
    }

    /**
     * 获取触发事件。任意事件迁移返回 {@code null}，请同时检查 {@link #isAnyEvent()}。
     *
     * @return 触发事件
     */
    public E getEvent() {
        return event;
    }

    /**
     * 获取固定目标状态。保持原状态的迁移返回 {@code null}，请同时检查 {@link #isStay()}。
     *
     * @return 固定目标状态
     */
    public S getTo() {
        return to;
    }

    /**
     * 判断是否为任意来源迁移。
     *
     * @return 任意来源时返回 {@code true}
     */
    public boolean isAnySource() {
        return anySource;
    }

    /**
     * 判断是否为任意事件迁移。
     *
     * @return 任意事件时返回 {@code true}
     */
    public boolean isAnyEvent() {
        return anyEvent;
    }

    /**
     * 判断是否为保持当前状态的迁移。
     *
     * @return 保持原状态时返回 {@code true}
     */
    public boolean isStay() {
        return stay;
    }

    /**
     * 判断是否带守卫。
     *
     * @return 带守卫时返回 {@code true}
     */
    public boolean isGuarded() {
        return guard != null;
    }

    /**
     * 判断是否带动作。
     *
     * @return 带动作时返回 {@code true}
     */
    public boolean hasAction() {
        return action != null;
    }

    /**
     * 获取守卫的可读描述，未声明守卫时返回 {@code null}。
     *
     * @return 守卫描述
     */
    public String getGuardDescription() {
        return guardDescription;
    }

    /**
     * 获取声明顺序，从 0 开始。同层规则按该顺序匹配。
     *
     * @return 声明顺序
     */
    int getDeclarationOrder() {
        return declarationOrder;
    }

    S resolveTarget(S currentState) {
        return stay ? currentState : to;
    }

    boolean isAllowed(TransitionContext<S, E, C> context) throws Exception {
        return guard == null || guard.test(context);
    }

    void execute(TransitionContext<S, E, C> context) throws Exception {
        if (action != null) {
            action.execute(context);
        }
    }

    @Override
    public String toString() {
        String source = anySource ? "*" : String.valueOf(from);
        String trigger = anyEvent ? "*" : String.valueOf(event);
        String target = stay ? "=" : String.valueOf(to);
        return id + ": " + source + " --(" + trigger + ")--> " + target;
    }
}
