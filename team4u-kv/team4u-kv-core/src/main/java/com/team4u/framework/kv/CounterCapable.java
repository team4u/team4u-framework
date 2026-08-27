package com.team4u.framework.kv;

/**
 * 原子计数能力
 * <p>
 * 键级单调递增计数器（对应 Redis {@code INCRBY}、数据库自增表达式、
 * 内存 {@code AtomicLong}），是序号生成（{@code team4u-id}）等
 * 计数型场景的基础。无法保证「读-改-写」原子性的实现不应实现本接口。
 * </p>
 *
 * <h3>实现契约</h3>
 * <ul>
 *     <li><b>初始值</b>：键不存在时从 {@code 0} 开始计数，
 *     首次调用返回 {@code delta}（不要求预先建键）</li>
 *     <li><b>原子性</b>：并发调用不丢失更新，返回值为本次调用
 *     递增后的精确当前值</li>
 *     <li><b>值域独立</b>：计数器与 {@link KvStore} 的字符串值域相互独立
 *     （JDBC/内存实现使用独立存储结构）。Redis 实现中计数键与普通值键
 *     共享物理键空间，同一键上混用两种语义行为未定义，调用方应避免</li>
 *     <li><b>生命周期</b>：计数器无过期语义，随键空间持续累加；
 *     周期重置应由调用方通过换键实现（如按日期拼接新键），旧键数据
 *     保留作审计，由调用方按需清理</li>
 * </ul>
 *
 * @author jay.wu
 */
public interface CounterCapable {

    /**
     * 原子递增计数器并返回递增后的值
     *
     * @param delta 递增量，通常为正（号段批量取号时可大于 1）
     * @return 递增后的当前值
     */
    long incrementAndGet(SpaceKey key, long delta);
}
