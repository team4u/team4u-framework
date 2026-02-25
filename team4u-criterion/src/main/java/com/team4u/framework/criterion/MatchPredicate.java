package com.team4u.framework.criterion;

/**
 * 匹配断言 (编译后的可执行函数)
 * <p>
 * 这是一个无状态或持有状态的 Lambda，执行时无需再查表，直接运行逻辑。
 */
@FunctionalInterface
public interface MatchPredicate {

    /**
     * 始终返回 false 的谓词
     */
    MatchPredicate FALSE = context -> false;

    /**
     * 始终返回 true 的谓词
     */
    MatchPredicate TRUE = context -> true;

    /**
     * 判断规则是否匹配
     *
     * @param context 匹配上下文
     * @return 是否匹配
     */
    boolean test(MatchContext context);
}
