package com.team4u.framework.kv;

/**
 * 原子计数能力
 * <p>
 * 键级单调递增计数器（对应 Redis {@code INCRBY}、数据库自增表达式、
 * 内存 {@code AtomicLong}），是序号生成（{@code team4u-id}）、
 * 限流（固定窗口计数）等计数型场景的基础。
 * 无法保证「读-改-写」原子性的实现不应实现本接口。
 * </p>
 *
 * <h3>实现契约</h3>
 * <ul>
 *     <li><b>初始值</b>：键不存在时从 {@code 0} 开始计数，
 *     首次调用返回 {@code delta}（不要求预先建键）</li>
 *     <li><b>原子性</b>：并发调用不丢失更新，返回值为本次调用
 *     递增后的精确当前值；TTL 的设置与过期判定同样在递增的
 *     原子操作内完成，不得出现「重置与累积分离」的中间态</li>
 *     <li><b>TTL 语义</b>：{@code ttlMillis > 0} 时计数键在
 *     {@code ttlMillis} 毫秒后过期，过期后的首次递增从 {@code 0}
 *     重新开始（返回值等于 {@code delta}）；TTL 在键创建时设置，
 *     与递增原子生效，后续递增<b>不刷新</b> TTL（存量无 TTL 键首次
 *     遇到 {@code ttlMillis > 0} 的递增时补充设置 TTL）。
 *     {@code ttlMillis <= 0} 表示永不过期（原语义）</li>
 *     <li><b>值域独立</b>：计数器与 {@link KvStore} 的字符串值域相互独立
 *     （JDBC/内存实现使用独立存储结构）。Redis 实现中计数键与普通值键
 *     共享物理键空间，同一键上混用两种语义行为未定义，调用方应避免</li>
 *     <li><b>生命周期</b>：{@code ttlMillis <= 0} 的计数器永不过期，
 *     随键空间持续累加，周期重置应由调用方通过换键实现（如按日期拼接新键）；
 *     {@code ttlMillis > 0} 的计数器过期后由惰性判定（递增时重置）
 *     与 {@code pruneExpired} 清扫回收存储空间</li>
 * </ul>
 *
 * @author jay.wu
 */
public interface CounterCapable {

    /**
     * 原子递增计数器并返回递增后的值
     *
     * @param delta     递增量，通常为正（号段批量取号时可大于 1）
     * @param ttlMillis 键过期时长（毫秒），大于 0 时键在 ttl 后过期、
     *                  过期后重新从 0 计数；小于等于 0 表示永不过期
     * @return 递增后的当前值
     */
    long incrementAndGet(SpaceKey key, long delta, long ttlMillis);
}
