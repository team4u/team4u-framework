package com.team4u.framework.router.api.model;

/**
 * 路由结果来源语义。
 * <p>
 * 用于区分本次路由是命中显式规则、命中兜底值、完全未命中，
 * 还是在上游流程中被短路并直接返回结果。
 * </p>
 */
public enum RouteOutcome {
    /**
     * 命中了显式路由规则。
     */
    RULE_MATCH,

    /**
     * 未命中显式规则，但命中了兜底值。
     */
    FALLBACK_MATCH,

    /**
     * 未命中任何规则，且没有可用的兜底值。
     */
    NO_MATCH,

    /**
     * 在路由处理链路中被短路，直接返回最终结果。
     * <p>
     * 通常由上游 {@code RouteInterceptor} 未继续调用 {@code proceed()}，
     * 而是直接构造并返回结果时产生。
     * </p>
     */
    SHORT_CIRCUITED;

    /**
     * 判断当前结果是否视为命中。
     *
     * @return 除 {@link #NO_MATCH} 外均返回 true
     */
    public boolean isMatch() {
        return this != NO_MATCH;
    }
}
