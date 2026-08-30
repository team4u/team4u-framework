package com.team4u.framework.flow;

/**
 * 可重放的外部网关扩展点，无框架管理的状态。before 决定是否放行，after 默认空实现。
 */
public interface Policy<K> {
    Gate before(PolicyContext context, K key);

    default void after(PolicyContext context, K key, Completion completion) { }
}
