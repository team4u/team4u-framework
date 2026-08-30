package com.team4u.framework.kv;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 计分窗口能力
 * <p>
 * 键级有序计分窗口（对应 Redis ZSET），支撑滑动窗口限流等
 * 「按 score 裁剪 + 上限准入 + 原子计数」场景。score 通常为时间戳
 * 等单调递增量，窗口语义完全由调用方定义，实现只关心大小关系。
 * 无法保证「裁剪→计数→添加」整体原子性的实现不应实现本接口
 * （能力可选，调用方经 {@link KvStores#capabilityOf} 协商，
 * 缺失时快速失败）。
 * </p>
 *
 * <h3>实现契约</h3>
 * <ul>
 *     <li><b>原子性</b>：整个「裁剪 → 计数 → 条件添加」在一次原子操作内完成，
 *     并发调用不产生中间态、不丢失成员</li>
 *     <li><b>裁剪</b>：score 严格大于 {@code cutoffScore} 的成员存活，
 *     score 等于 {@code cutoffScore} 的成员视为过期被裁剪</li>
 *     <li><b>条件添加</b>：members 非空且「裁剪后计数 + members 数量」
 *     超过 {@code maxCount} 时，<b>不添加任何成员</b>并返回
 *     {@code accepted=false}；未超限时全部添加并返回 {@code accepted=true}</li>
 *     <li><b>窥探</b>：members 为空表示仅裁剪与计数，不添加成员，
 *     永不拒绝（{@code accepted=true}）</li>
 *     <li><b>TTL</b>：{@code ttlMillis > 0} 时每次成功操作（含窥探）
 *     刷新整个键的过期时间，键过期后整键消失、窗口从零重来；
 *     {@code ttlMillis <= 0} 表示永不过期。TTL 是键卫生手段
 *     （清理零流量残留键），与按 score 裁剪是两套独立机制</li>
 *     <li><b>oldestScore</b>：裁剪后现存成员中的最小 score
 *     （最老成员），窗口为空时为 {@code null}</li>
 * </ul>
 *
 * @author jay.wu
 */
public interface ScoredWindowCapable {

    /**
     * 向计分窗口提交一次「裁剪 → 计数 → 条件添加」的原子操作
     *
     * @param key   窗口键
     * @param offer 操作参数，见 {@link Offer}
     * @return 操作结果，见 {@link Verdict}
     */
    Verdict offer(SpaceKey key, Offer offer);

    /**
     * 一次窗口操作的参数
     *
     * @author jay.wu
     */
    @Getter
    @Builder
    class Offer {

        /**
         * 裁剪阈值：score &lt;= 此值的成员视为过期，被裁剪
         */
        private final long cutoffScore;

        /**
         * 新成员的 score（members 中的所有成员共用此 score）
         */
        private final long memberScore;

        /**
         * 待添加成员；为空表示「窥探」——仅裁剪计数，不添加，永不拒绝
         */
        private final List<String> members;

        /**
         * 成员数量上限：裁剪后计数 + members 数量超过此值则拒绝添加
         */
        private final int maxCount;

        /**
         * 键卫生 TTL（毫秒）：&gt; 0 时每次成功操作刷新整个键的过期时间，
         * 键过期后整键消失重来；&lt;= 0 表示永不过期
         */
        private final long ttlMillis;
    }

    /**
     * 一次窗口操作的结果
     *
     * @author jay.wu
     */
    @Getter
    @Builder
    class Verdict {

        /**
         * 是否接受：members 非空时 = 裁剪后 count + members 数量
         * &lt;= maxCount 且已添加；members 为空（窥探）恒为 true
         */
        private final boolean accepted;

        /**
         * 裁剪后计数；accepted 时含新成员
         */
        private final long count;

        /**
         * 裁剪后现存最老成员 score；count == 0 时为 null
         */
        private final Long oldestScore;
    }
}
