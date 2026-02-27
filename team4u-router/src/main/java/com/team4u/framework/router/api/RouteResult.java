package com.team4u.framework.router.api;

import lombok.Data;

/**
 * 路由结果
 *
 * @param <T> 结果类型
 */
@Data
public class RouteResult<T> {

    private final boolean match;
    private final T value;
    /**
     * 命中的规则条件
     * <ul>
     * <li>对于 MapRouter，为命中的 Key</li>
     * <li>对于 ExpressionRouter，为命中的表达式</li>
     * <li>对于兜底逻辑 (Fallback) 或未匹配，通常为 null</li>
     * </ul>
     */
    private final String matchedCondition;

    private RouteResult(boolean match, T value, String matchedCondition) {
        this.match = match;
        this.value = value;
        this.matchedCondition = matchedCondition;
    }

    /**
     * 匹配成功 (兜底匹配或无条件匹配)
     *
     * @param value 匹配值
     * @param <T>   结果类型
     * @return 路由结果
     */
    public static <T> RouteResult<T> matched(T value) {
        return new RouteResult<>(true, value, null);
    }

    /**
     * 匹配成功
     *
     * @param value            匹配值
     * @param matchedCondition 命中条件
     * @param <T>              结果类型
     * @return 路由结果
     */
    public static <T> RouteResult<T> matched(T value, String matchedCondition) {
        return new RouteResult<>(true, value, matchedCondition);
    }

    /**
     * 匹配失败
     *
     * @param <T> 结果类型
     * @return 路由结果
     */
    public static <T> RouteResult<T> unmatch() {
        return new RouteResult<>(false, null, null);
    }

    public boolean isNotMatch() {
        return !match;
    }
}
