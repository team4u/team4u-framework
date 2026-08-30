package com.team4u.framework.lease.spi;

import com.team4u.framework.base.util.Expiry;

/**
 * 租约时间戳计算（入口校验语义）
 * <p>
 * 本类是 lease 侧时间运算的<b>配置校验层</b>：在命令/参数进入后端时显式拒绝
 * 荒谬值（负数 now/duration、now + duration 上溢），而不是让溢出值流入存储。
 * 与 base 的 {@link Expiry} 饱和策略的分工是：
 * </p>
 * <ul>
 *     <li><b>本类（校验层）</b>：拒绝会让时间戳回绕的极端配置，调用方拿到的
 *     结果保证是真实的时间戳（绝不等于 {@link Expiry#NEVER} 哨兵），
 *     租约/重试延迟等用户可配置项在入口即失败，错误可定位；</li>
 *     <li><b>{@link Expiry}（运行期饱和层）</b>：存储与运行期已持有合法时间戳的
 *     增量计算走饱和加法（上溢封顶为 NEVER），不会因罕见边界抛异常。</li>
 * </ul>
 * <p>
 * 两种策略在 lease 模块的契约是「入口显式拒绝」：参见
 * {@code AbstractLeaseEpochOverflowContractTest}——巨大的提交延迟/租约时长/心跳延长
 * 必须以 IllegalArgumentException 拒绝且不产生任何副作用，因此本类保留抛异常语义。
 * 溢出探测使用与 {@link Expiry#saturatedAdd} 一致的符号翻转判定。
 * </p>
 *
 * @author jay.wu
 */
public final class LeaseTimes {

    private LeaseTimes() {
    }

    /**
     * 计算自 now 起 duration 毫秒后的时间戳，溢出显式拒绝
     * <p>
     * 校验规则：now 与 duration 均非负，且 {@code now + duration} 不超出
     * {@link Long#MAX_VALUE}。任一规则不满足即抛出
     * {@link IllegalArgumentException}，异常消息包含违规参数名与数值。
     *
     * @param now      起始时间（epoch 毫秒，非负）
     * @param duration 时长（毫秒，非负）
     * @return now + duration（保证不溢出）
     * @throws IllegalArgumentException now/duration 为负，或相加溢出 Long.MAX_VALUE
     */
    public static long plusMillis(long now, long duration) {
        if (now < 0L) {
            throw new IllegalArgumentException("now must not be negative: " + now);
        }
        if (duration < 0L) {
            throw new IllegalArgumentException("duration must not be negative: " + duration);
        }
        long sum = now + duration;
        // 非负操作数相加符号翻转 = 真实上溢（区别于饱和结果恰为 MAX_VALUE 的合法情形，
        // 如 Long.MAX_VALUE + 0）；校验层对真实上溢显式拒绝，不采用 Expiry 的 NEVER 封顶
        if (((now ^ sum) & (duration ^ sum)) < 0L) {
            throw new IllegalArgumentException("now + duration overflows Long.MAX_VALUE: "
                    + now + " + " + duration);
        }
        return sum;
    }
}
