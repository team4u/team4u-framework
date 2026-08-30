package com.team4u.framework.base.util;

/**
 * 过期时间戳工具类
 * <p>
 * 统一「计算过期时间戳」的溢出策略：{@code now + ttl} 以<b>饱和加法</b>执行，
 * 上溢时封顶为 {@link Long#MAX_VALUE}（即 {@link #NEVER}），而非抛出异常。
 * </p>
 * <p>
 * <b>为什么选择饱和而不是抛异常</b>：
 * </p>
 * <ul>
 *     <li>时间戳上溢的语义是「极远的未来」，对租约/TTL 场景等价于「永不过期」，
 *     是一个有明确定义且安全的结果；而抛异常会把一个极端但可接受的配置值
 *     （如超长 TTL）放大为运行时故障，迫使调用方为罕见分支编写处理代码。</li>
 *     <li>饱和策略与存储侧既有饱和行为语义一致（KvRecord/TieredStore/ExpiringValue
 *     原先各自私有地饱和至 Long.MAX_VALUE，本类是这类私有实现的统一收口点），
 *     统一后行为不变、心智一致；lease 侧原先的
 *     抛异常策略（LeaseTimes）由各调用方在「校验配置」阶段显式约束，
 *     而非在「计算时间戳」这一纯函数里混杂失败路径。</li>
 *     <li>纯函数无异常路径更利于组合：调用方可以用统一的
 *     {@code max(0, expiry - now)} 计算剩余时间，而不必先做范围预判。</li>
 * </ul>
 * <p>
 * 本类只负责时间戳运算，不感知「0 表示永不过期」等模块私有哨兵语义
 * （如 KvRecord 以 expireAt=0 表示永不过期），模块如需该语义请在自身边界转换。
 * </p>
 *
 * @author jay.wu
 */
public final class Expiry {

    /**
     * 永不过期的哨兵值（饱和上溢的目标值）
     */
    public static final long NEVER = Long.MAX_VALUE;

    private Expiry() {
    }

    /**
     * 计算自当前墙钟时间起 ttl 毫秒后的过期时间戳（上溢饱和为 {@link #NEVER}）
     * <p>
     * 等价于 {@code expiryFrom(System.currentTimeMillis(), ttlMillis)}。
     * 需要注入时钟（测试或虚拟时间）的场景请使用 {@link #expiryFrom(long, long)}。
     * </p>
     *
     * @param ttlMillis 有效时长（毫秒）；传 {@link #NEVER} 时结果饱和为 {@link #NEVER}
     * @return 过期时间戳（epoch 毫秒）
     */
    public static long expiryFromNow(long ttlMillis) {
        return expiryFrom(System.currentTimeMillis(), ttlMillis);
    }

    /**
     * 计算自指定时间起 ttl 毫秒后的过期时间戳（上溢饱和为 {@link #NEVER}）
     *
     * @param now      起始时间（epoch 毫秒，约定非负）
     * @param ttlMillis 有效时长（毫秒）；传 {@link #NEVER} 时结果饱和为 {@link #NEVER}
     * @return 过期时间戳（epoch 毫秒）；now + ttl 上溢时返回 {@link #NEVER}
     */
    public static long expiryFrom(long now, long ttlMillis) {
        return saturatedAdd(now, ttlMillis);
    }

    /**
     * 计算距过期还剩多少毫秒（基于当前墙钟时间）
     *
     * @param expiryMillis 过期时间戳（epoch 毫秒）
     * @return 剩余毫秒数；<b>已过期返回 0（不返回负数）</b>，便于直接用作等待时长；
     * {@link #NEVER} 返回 {@link #NEVER}
     */
    public static long remainingMillis(long expiryMillis) {
        return remainingMillis(expiryMillis, System.currentTimeMillis());
    }

    /**
     * 计算距过期还剩多少毫秒（基于指定时间）
     *
     * @param expiryMillis 过期时间戳（epoch 毫秒）
     * @param now         当前时间（epoch 毫秒，约定非负）
     * @return 剩余毫秒数；<b>已过期（now &gt;= expiry）返回 0（不返回负数）</b>，
     * 便于直接用作等待时长；{@link #NEVER} 返回 {@link #NEVER}
     */
    public static long remainingMillis(long expiryMillis, long now) {
        if (expiryMillis == NEVER) {
            return NEVER;
        }
        long remaining = expiryMillis - now;
        return remaining > 0L ? remaining : 0L;
    }

    /**
     * 判断是否已过期（基于当前墙钟时间）
     *
     * @param expiryMillis 过期时间戳（epoch 毫秒）
     * @return {@code true} 表示已过期（now &gt;= expiry）；{@link #NEVER} 永远返回 {@code false}
     */
    public static boolean isExpired(long expiryMillis) {
        return isExpired(expiryMillis, System.currentTimeMillis());
    }

    /**
     * 判断是否已过期（基于指定时间）
     *
     * @param expiryMillis 过期时间戳（epoch 毫秒）
     * @param now         当前时间（epoch 毫秒）
     * @return {@code true} 表示已过期（now &gt;= expiry，到达过期时刻即视为过期）
     */
    public static boolean isExpired(long expiryMillis, long now) {
        return now >= expiryMillis;
    }

    /**
     * 非负饱和加法：a + b 上溢时返回 {@link Long#MAX_VALUE}
     * <p>
     * 本类溢出策略的底层实现，也可用于其他需要「时间戳/计数器累加防溢出」的场景
     * （此前 RefreshableValue、ExpiringValue、TieredStore 各自持有同款私有实现）。
     * 约定 a、b 非负；同号相加发生符号翻转即判定为溢出。
     * </p>
     *
     * @param a 加数（约定非负）
     * @param b 加数（约定非负）
     * @return a + b；上溢时返回 {@link Long#MAX_VALUE}
     */
    public static long saturatedAdd(long a, long b) {
        long sum = a + b;
        // 同号相加结果符号翻转 = 溢出；非负输入下即上溢
        if (((a ^ sum) & (b ^ sum)) < 0L) {
            return Long.MAX_VALUE;
        }
        return sum;
    }
}
