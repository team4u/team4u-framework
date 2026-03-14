package com.team4u.framework.router.api.model;

import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * 路由执行结果 (Routing Result Object)
 * <p>
 * 该类是一个不可变值对象，承载了一次路由执行的完整产出。
 * 它不仅包含了最终匹配到的业务值，还通过语义化的状态码（Outcome）和匹配条件轨迹提供诊断信息。
 * </p>
 *
 * @param <T> 业务结果的泛型类型
 * @author jay.wu
 */
@Value
public class RouteResult<T> {

    /**
     * 匹配失败的共享单例。
     * 用于在未命中任何规则时减少内存分配开销。
     */
    private static final RouteResult<?> UNMATCH_INSTANCE = new RouteResult<>(RouteOutcome.NO_MATCH, null, null);

    /**
     * 路由匹配的最终语义结论。
     * 指明结果是来自具体的业务规则、系统兜底逻辑，还是由于上游拦截而短路。
     */
    RouteOutcome outcome;

    /**
     * 命中的业务路由值。
     * 如果 {@link #outcome} 为 {@code NO_MATCH}，该值通常为 {@code null}。
     */
    T value;

    /**
     * 触发匹配的原始条件记录（逻辑证据）。
     * <ul>
     *   <li>在单规则匹配模式下，通常包含命中的表达式字符串或映射键。</li>
     *   <li>在多重匹配模式下，按命中顺序记录所有满足条件的表达式。</li>
     * </ul>
     */
    List<String> matchedConditions;

    private RouteResult(RouteOutcome outcome, T value, List<String> matchedConditions) {
        this.outcome = outcome;
        this.value = value;
        // 转换成不可变集合，确保结果对象的线程安全性与稳定性
        this.matchedConditions = matchedConditions != null
                ? Collections.unmodifiableList(matchedConditions)
                : null;
    }

    /**
     * 构造一个基于“显式规则”命中的路由结果（单条件）。
     *
     * @param value            命中的业务值
     * @param matchedCondition 触发命中的特定条件表达式或键名
     * @param <T>              结果类型
     * @return 路由结果实例
     */
    public static <T> RouteResult<T> matched(T value, String matchedCondition) {
        return ruleMatch(value, matchedCondition);
    }

    /**
     * 构造一个基于“显式规则”命中的路由结果（单条件）。
     *
     * @param value            命中的业务值
     * @param matchedCondition 触发命中的特定条件表达式或键名
     * @param <T>              结果类型
     * @return 路由结果实例
     */
    public static <T> RouteResult<T> ruleMatch(T value, String matchedCondition) {
        List<String> conditions = matchedCondition != null ? Collections.singletonList(matchedCondition) : null;
        return new RouteResult<>(RouteOutcome.RULE_MATCH, value, conditions);
    }

    /**
     * 构造一个基于“显式规则”命中的路由结果（多重条件）。
     *
     * @param value             命中的业务值集合（通常 T 为 List）
     * @param matchedConditions 触发命中的所有条件列表
     * @param <T>               结果类型
     * @return 路由结果实例
     */
    public static <T> RouteResult<T> matched(T value, List<String> matchedConditions) {
        return ruleMatch(value, matchedConditions);
    }

    /**
     * 构造一个基于“显式规则”命中的路由结果（多重条件）。
     *
     * @param value             命中的业务值集合
     * @param matchedConditions 触发命中的所有条件列表
     * @param <T>               结果类型
     * @return 路由结果实例
     */
    public static <T> RouteResult<T> ruleMatch(T value, List<String> matchedConditions) {
        return new RouteResult<>(RouteOutcome.RULE_MATCH, value, matchedConditions);
    }

    /**
     * 构造一个基于“兜底策略”命中的路由结果。
     * 表示所有业务规则均未满足，使用了配置中预设的 fallback 值。
     *
     * @param value 兜底业务值
     * @param <T>   结果类型
     * @return 路由结果实例
     */
    public static <T> RouteResult<T> fallbackMatch(T value) {
        return new RouteResult<>(RouteOutcome.FALLBACK_MATCH, value, null);
    }

    /**
     * 构造一个被拦截器“强制短路（Short-Circuited）”的路由结果。
     * 表示路由逻辑并未真正穿透到底层路由器，而是被上游插件直接中断并返回。
     *
     * @param value 拦截器提供的强制结果值
     * @param <T>   结果类型
     * @return 路由结果实例
     */
    public static <T> RouteResult<T> shortCircuited(T value) {
        return new RouteResult<>(RouteOutcome.SHORT_CIRCUITED, value, null);
    }

    /**
     * 构造一个被拦截器“强制短路（Short-Circuited）”的路由结果（带条件证据）。
     *
     * @param value            拦截器提供的结果值
     * @param matchedCondition 导致短路的决策依据
     * @param <T>              结果类型
     * @return 路由结果实例
     */
    public static <T> RouteResult<T> shortCircuited(T value, String matchedCondition) {
        List<String> conditions = matchedCondition != null ? Collections.singletonList(matchedCondition) : null;
        return new RouteResult<>(RouteOutcome.SHORT_CIRCUITED, value, conditions);
    }

    /**
     * 构造一个被拦截器“强制短路（Short-Circuited）”的路由结果（带多条负载证据）。
     *
     * @param value             拦截器提供的结果值
     * @param matchedConditions 导致短路的多个决策依据
     * @param <T>               结果类型
     * @return 路由结果实例
     */
    public static <T> RouteResult<T> shortCircuited(T value, List<String> matchedConditions) {
        return new RouteResult<>(RouteOutcome.SHORT_CIRCUITED, value, matchedConditions);
    }

    /**
     * 获取表示“未命中”的共享单例对象。
     *
     * @param <T> 结果类型
     * @return 未匹配语义的路由结果
     */
    @SuppressWarnings("unchecked")
    public static <T> RouteResult<T> unmatch() {
        return (RouteResult<T>) UNMATCH_INSTANCE;
    }

    /**
     * 判断路由是否匹配成功。
     * 匹配成功包括：规则命中、兜底命中或中途短路。
     *
     * @return 如果整体流程有产出结果则返回 true
     */
    public boolean isMatch() {
        return outcome.isMatch();
    }

    /**
     * 判断路由是否完全未匹配（无产出）。
     *
     * @return 如果未匹配返回 true
     */
    public boolean isNotMatch() {
        return !isMatch();
    }

    /**
     * 判断是否命中了显式定义的业务规则。
     *
     * @return 如果是显式规则命中则返回 true
     */
    public boolean isRuleMatch() {
        return outcome == RouteOutcome.RULE_MATCH;
    }

    /**
     * 判断是否命中了兜底策略。
     *
     * @return 如果是兜底命中则返回 true
     */
    public boolean isFallbackMatch() {
        return outcome == RouteOutcome.FALLBACK_MATCH;
    }

    /**
     * 判断是否在中途被拦截器短路。
     *
     * @return 如果被短路则返回 true
     */
    public boolean isShortCircuited() {
        return outcome == RouteOutcome.SHORT_CIRCUITED;
    }

    /**
     * 获取第一个命中规则的条件描述。
     * <p>
     * 在单匹配场景下非常有用；若是多重匹配，则仅返回列表中的第一个条目。
     * </p>
     *
     * @return 命中条件的字符串描述，若无则返回 null
     */
    public String getMatchedCondition() {
        if (matchedConditions != null && !matchedConditions.isEmpty()) {
            return matchedConditions.get(0);
        }
        return null;
    }
}
