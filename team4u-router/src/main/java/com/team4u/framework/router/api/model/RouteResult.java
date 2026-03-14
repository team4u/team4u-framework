package com.team4u.framework.router.api.model;

import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * 路由结果
 * <p>
 * 不可变对象，表示一次路由操作的结果。包含匹配状态、路由值和命中的条件。
 * </p>
 *
 * @param <T> 结果类型
 * @author jay.wu
 */
@Value
public class RouteResult<T> {

    /**
     * 匹配失败的单例对象，用于减少不必要的对象创建。
     * </p>
     */
    private static final RouteResult<?> UNMATCH_INSTANCE = new RouteResult<>(RouteOutcome.NO_MATCH, null, null);

    RouteOutcome outcome;
    T value;
    List<String> matchedConditions;

    private RouteResult(RouteOutcome outcome, T value, List<String> matchedConditions) {
        this.outcome = outcome;
        this.value = value;
        // 使用不可变列表确保对象的不可变性
        this.matchedConditions = matchedConditions != null
                ? Collections.unmodifiableList(matchedConditions)
                : null;
    }

    /**
     * 匹配成功 (兜底匹配或无条件匹配)
     *
     * @param value 匹配值
     * @param <T>   结果类型
     * @return 路由结果
     */
    @Deprecated
    public static <T> RouteResult<T> matched(T value) {
        return fallbackMatch(value);
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
        return ruleMatch(value, matchedCondition);
    }

    public static <T> RouteResult<T> ruleMatch(T value, String matchedCondition) {
        List<String> conditions = matchedCondition != null ? Collections.singletonList(matchedCondition) : null;
        return new RouteResult<>(RouteOutcome.RULE_MATCH, value, conditions);
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
        return ruleMatch(value, matchedConditions);
    }

    public static <T> RouteResult<T> ruleMatch(T value, List<String> matchedConditions) {
        return new RouteResult<>(RouteOutcome.RULE_MATCH, value, matchedConditions);
    }

    public static <T> RouteResult<T> fallbackMatch(T value) {
        return new RouteResult<>(RouteOutcome.FALLBACK_MATCH, value, null);
    }

    public static <T> RouteResult<T> shortCircuited(T value) {
        return new RouteResult<>(RouteOutcome.SHORT_CIRCUITED, value, null);
    }

    public static <T> RouteResult<T> shortCircuited(T value, String matchedCondition) {
        List<String> conditions = matchedCondition != null ? Collections.singletonList(matchedCondition) : null;
        return new RouteResult<>(RouteOutcome.SHORT_CIRCUITED, value, conditions);
    }

    public static <T> RouteResult<T> shortCircuited(T value, List<String> matchedConditions) {
        return new RouteResult<>(RouteOutcome.SHORT_CIRCUITED, value, matchedConditions);
    }

    /**
     * 匹配失败
     * <p>
     * 返回一个共享的未匹配实例，避免不必要的对象创建。
     * </p>
     *
     * @param <T> 结果类型
     * @return 未匹配的路由结果
     */
    @SuppressWarnings("unchecked")
    public static <T> RouteResult<T> unmatch() {
        return (RouteResult<T>) UNMATCH_INSTANCE;
    }

    public boolean isMatch() {
        return outcome.isMatch();
    }

    /**
     * 判断是否未匹配
     *
     * @return 如果未匹配返回 true
     */
    public boolean isNotMatch() {
        return !isMatch();
    }

    public boolean isRuleMatch() {
        return outcome == RouteOutcome.RULE_MATCH;
    }

    public boolean isFallbackMatch() {
        return outcome == RouteOutcome.FALLBACK_MATCH;
    }

    public boolean isShortCircuited() {
        return outcome == RouteOutcome.SHORT_CIRCUITED;
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
