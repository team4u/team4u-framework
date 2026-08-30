package com.team4u.framework.policy.api;

/**
 * 仅提供排序功能的策略接口（无上下文匹配需求）
 * <p>
 * 继承自 {@link ContextPolicy}，将泛型上下文替换为 {@link Void}。
 * 默认实现 {@link #supports(Void)} 方法，始终返回 true，从而免除子类实现。
 * 适用于无需进行条件匹配、仅需依据优先级（priority）进行排序的策略。
 *
 * @author jay.wu
 */
public interface OrderedPolicy extends ContextPolicy<Void> {

    /**
     * 始终支持处理
     *
     * @param context 无实际意义的上下文
     * @return 始终返回 true
     */
    @Override
    default boolean supports(Void context) {
        return true;
    }
}
