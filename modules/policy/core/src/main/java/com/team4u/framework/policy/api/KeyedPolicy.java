package com.team4u.framework.policy.api;

/**
 * 键值路由策略 (用于 O(1) 极速匹配)
 * <p>
 * 分配固定的路由键进行匹配，实现精准、高效的策略路由。
 *
 * @param <K> 路由键的类型，例如字符串类型的渠道名或整数类型的单据类型
 */
public interface KeyedPolicy<K> {

    /**
     * 当前策略绑定的标识键
     *
     * @return 路由键标识
     */
    K key();
}
