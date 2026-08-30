package com.team4u.framework.kv;

/**
 * 原子比较替换能力
 * <p>
 * 基于存储值精确匹配（字符串相等）的 CAS 语义，是 fencing 安全的锁、
 * 所有权安全的续期/释放（{@code team4u-kv-lock}）的基础。
 * 无法保证原子性的存储不应实现本接口，锁会在构造期快速失败。
 * </p>
 *
 * @author jay.wu
 */
public interface CasCapable {

    /**
     * 仅当存储中的存活记录值与 {@code expectedValue} 精确相等时，替换为新记录
     *
     * @return {@code true} 替换成功；键不存在、已过期或值不匹配返回 {@code false}
     */
    boolean compareAndSet(SpaceKey key, String expectedValue, KvRecord update);

    /**
     * 仅当存储中的存活记录值与 {@code expectedValue} 精确相等时删除
     *
     * @return {@code true} 删除成功；键不存在、已过期或值不匹配返回 {@code false}
     */
    boolean compareAndRemove(SpaceKey key, String expectedValue);

    /**
     * 仅当存储中的存活记录值与 {@code expectedValue} 精确相等时，
     * 原子地更新过期时间为 {@code newExpireAtMillis}（单次存储往返）
     * <p>
     * 语义（对照 {@link #compareAndSet} 的「值匹配才动」风格，只动过期时间、不动值）：
     * </p>
     * <ul>
     *     <li><b>所有权校验</b>：键不存在、已过期或值不匹配时返回 {@code false}，
     *     记录保持不变——绝不续期他人的记录，也绝不复活已过期的记录
     *     （过期与值校验必须在同一原子操作内判定，防止「读时尚存活、写时已过期」
     *     的窗口期误续约）</li>
     *     <li><b>晚到续约不缩短租约</b>：仅当新过期时间<b>晚于</b>当前过期时间时生效
     *     （{@code newExpireAtMillis} 按与 {@link KvRecord#getExpireAt()} 一致的
     *     epoch 毫秒比较，{@code 0} 表示永不过期、视为无穷大）；新值早于当前值时
     *     保留当前值（乱序到达的延迟心跳不得回缩租约，参考 team4u-lease 的
     *     JdbcLeaseTaskDao.heartbeat 条件 UPDATE 写法）</li>
     *     <li><b>返回值</b>：{@code true} 表示持有者校验通过且记录存活
     *     （含「因保序保护未变更过期时间」的情形——此时租约仍不短于请求值）；
     *     {@code false} 表示所有权已丢失（键不存在、已过期或值不匹配）</li>
     * </ul>
     * <p>
     * <b>实现方要求</b>：整个「校验 + 更新」必须一次存储往返原子完成
     * （数据库条件 UPDATE、Redis 单 Lua 脚本、内存 compute 等），
     * 不得组合 {@code get} + {@link #compareAndSet} 两段式实现——两段式在
     * 窗口期内会用陈旧快照续约，正是本方法要消除的缺陷。
     * 实在无法单往返原子完成的实现，必须在本方法的实现 Javadoc 中显著标注
     * 「非原子退化实现」及风险说明，供调用方评估。
     * </p>
     *
     * @param newExpireAtMillis 新过期时间戳（epoch 毫秒），0 表示改为永不过期
     * @return {@code true} 持有者校验通过且记录存活；键不存在、已过期或值不匹配返回 {@code false}
     */
    boolean compareAndExpire(SpaceKey key, String expectedValue, long newExpireAtMillis);
}
