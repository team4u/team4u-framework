package com.team4u.framework.singleflight.config;

/**
 * 存储故障策略：协调存储（锁 / 会话 / 缓存）读写抛 {@code KvStoreException} 时的处置方式。
 * <p>
 * 规则显式配置优先；规则省略该字段时按竞争策略推导——WAIT / FALLBACK 默认
 * {@link #PASS_THROUGH}，FAIL_FAST 默认 {@link #FAIL_CLOSED}（本身就是拒绝语义，
 * 放行会静默破坏互斥保证）。
 * </p>
 *
 * @author jay.wu
 */
public enum StoreFailurePolicy {

    /**
     * 跳过协调，直接执行加载函数（fail-open）：牺牲合并保证换取业务可用性。
     */
    PASS_THROUGH,

    /**
     * 以组件异常终止本次执行（fail-closed）：宁可失败也不放过重复执行。
     */
    FAIL_CLOSED
}
