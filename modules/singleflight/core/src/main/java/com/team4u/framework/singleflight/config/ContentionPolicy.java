package com.team4u.framework.singleflight.config;

/**
 * 锁竞争策略：执行权被其他调用者持有时，本次调用的收尾方式。
 * <ul>
 *     <li>{@link #WAIT}：轮询等待终态结果或接管机会——缓存击穿合并的典型选择</li>
 *     <li>{@link #FAIL_FAST}：立即抛无栈的冲突异常——并发窗口互斥、任务防重</li>
 *     <li>{@link #FALLBACK}：返回规则配置的降级值——调用方拿到廉价兜底而非失败</li>
 * </ul>
 *
 * @author jay.wu
 */
public enum ContentionPolicy {
    WAIT,
    FAIL_FAST,
    FALLBACK
}
