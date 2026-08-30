package com.team4u.framework.fsm;

/**
 * 迁移守卫。守卫应只做判定，不应产生业务副作用或修改传入的上下文。
 * <p>
 * 守卫抛出的异常会被包装为 {@code TransitionExecutionException}（阶段为 GUARD），
 * 不再尝试同层后续规则。
 *
 * @param <S> 状态类型
 * @param <E> 事件类型
 * @param <C> 业务上下文类型
 * @author jay.wu
 */
@FunctionalInterface
public interface TransitionGuard<S, E, C> {

    /**
     * 判断当前迁移是否允许执行。
     *
     * @param context 迁移上下文
     * @return 允许执行时返回 {@code true}
     * @throws Exception 守卫执行失败
     */
    boolean test(TransitionContext<S, E, C> context) throws Exception;

    /**
     * 返回与另一个守卫进行短路与运算后的守卫。
     *
     * @param other 另一个守卫，非空
     * @return 组合守卫
     */
    default TransitionGuard<S, E, C> and(final TransitionGuard<S, E, C> other) {
        if (other == null) {
            throw new IllegalArgumentException("Other transition guard cannot be null");
        }
        return context -> test(context) && other.test(context);
    }

    /**
     * 返回与另一个守卫进行短路或运算后的守卫。
     *
     * @param other 另一个守卫，非空
     * @return 组合守卫
     */
    default TransitionGuard<S, E, C> or(final TransitionGuard<S, E, C> other) {
        if (other == null) {
            throw new IllegalArgumentException("Other transition guard cannot be null");
        }
        return context -> test(context) || other.test(context);
    }

    /**
     * 返回当前守卫的取反守卫。
     *
     * @return 取反守卫
     */
    default TransitionGuard<S, E, C> negate() {
        return context -> !test(context);
    }
}
