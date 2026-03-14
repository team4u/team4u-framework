package com.team4u.framework.router.api.model;

/**
 * 路由结果来源语义。
 */
public enum RouteOutcome {
    RULE_MATCH,
    FALLBACK_MATCH,
    NO_MATCH,
    SHORT_CIRCUITED;

    public boolean isMatch() {
        return this != NO_MATCH;
    }
}
