package com.team4u.framework.fsm;

/**
 * 状态迁移动作。
 * <p>
 * 动作按声明顺序执行，无法改写目标状态（目标状态在构建期已固定）；
 * 任一动作抛出异常即终止后续动作，本次迁移视为失败，异常会被包装为
 * {@code TransitionExecutionException}（阶段为 ACTION）。
 *
 * @param <S> 状态类型
 * @param <E> 事件类型
 * @param <C> 业务上下文类型
 * @author jay.wu
 */
@FunctionalInterface
public interface TransitionAction<S, E, C> {

    /**
     * 执行迁移附带的业务动作。
     *
     * @param context 迁移上下文
     * @throws Exception 动作执行失败
     */
    void execute(TransitionContext<S, E, C> context) throws Exception;

    /**
     * 将另一个动作追加到当前动作之后。
     *
     * @param next 后续动作，非空
     * @return 顺序组合后的动作
     */
    default TransitionAction<S, E, C> andThen(final TransitionAction<S, E, C> next) {
        if (next == null) {
            throw new IllegalArgumentException("Next transition action cannot be null");
        }
        return context -> {
            execute(context);
            next.execute(context);
        };
    }
}
