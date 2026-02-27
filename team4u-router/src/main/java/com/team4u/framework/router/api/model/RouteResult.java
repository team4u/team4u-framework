package com.team4u.framework.router.api.model;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 路由结果
 *
 * @param <T> 结果类型
 */
@Data
public class RouteResult<T> {

    /**
     * 匹配失败的单例对象，用于减少不必要的对象创建
     */
    private static final RouteResult<?> UNMATCH_INSTANCE = new RouteResult<>(false, null, null);
    private final boolean match;
    private final T value;
    private final List<String> matchedConditions;

    private RouteResult(boolean match, T value, List<String> matchedConditions) {
        this.match = match;
        this.value = value;
        this.matchedConditions = matchedConditions;
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
     * 匹配成功 (单条件匹配)
     *
     * @param value            匹配值
     * @param matchedCondition 命中条件
     * @param <T>              结果类型
     * @return 路由结果
     */
    public static <T> RouteResult<T> matched(T value, String matchedCondition) {
        List<String> conditions = matchedCondition != null ? Collections.singletonList(matchedCondition) : null;
        return new RouteResult<>(true, value, conditions);
    }

    /**
     * 匹配成功 (多重匹配)
     *
     * @param value             匹配值
     * @param matchedConditions 命中的所有条件
     * @param <T>               结果类型
     * @return 路由结果
     */
    public static <T> RouteResult<T> matched(T value, List<String> matchedConditions) {
        return new RouteResult<>(true, value, matchedConditions);
    }

    /**
     * 匹配失败
     *
     * @param <T> 结果类型
     * @return 路由结果
     */
    @SuppressWarnings("unchecked")
    public static <T> RouteResult<T> unmatch() {
        return (RouteResult<T>) UNMATCH_INSTANCE;
    }

    public boolean isNotMatch() {
        return !match;
    }

    /**
     * 获取单个命中的规则条件（返回第一个匹配项）
     * <p>
     * 提供此便利方法用于向后兼容和只需要单个条件的场景。
     * 如果是多重匹配模式，建议直接使用 {@link #getMatchedConditions()}。
     * </p>
     *
     * @return 第一个匹配的条件，如果没有命中条件则返回 null
     */
    public String getMatchedCondition() {
        if (matchedConditions != null && !matchedConditions.isEmpty()) {
            return matchedConditions.get(0);
        }
        return null;
    }
}
