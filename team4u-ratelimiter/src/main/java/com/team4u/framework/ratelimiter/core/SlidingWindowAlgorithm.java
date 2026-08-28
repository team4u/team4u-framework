package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.ScoredWindowCapable;
import com.team4u.framework.kv.ScoredWindowCapable.Offer;
import com.team4u.framework.kv.ScoredWindowCapable.Verdict;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.ratelimiter.api.RateLimitConfigException;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.config.RateLimitRule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 滑动窗口算法（精确滚动语义）
 * <p>
 * 基于 {@link ScoredWindowCapable#offer} 的原子「裁剪 → 计数 → 条件添加」：
 * 以请求时刻为成员 score，裁剪 {@code score <= now - windowMillis} 的过期成员后，
 * 计数 + 本次许可数不超过 {@code threshold}（maxCount）即放行。
 * 窗口随每次请求精确滚动（非固定对齐），窗口边缘的突发在下一时刻即可重新获得额度。
 * </p>
 * <p>
 * 成员 id 为 {@code nowMillis-hexRandom-i} 随机串（同一请求的多个许可各自唯一）；
 * 键 TTL = 窗口时长（每次成功操作刷新，清理零流量残留键）。{@code permits == 0}
 * 为窥探：members 空、不添加成员、永不因容量拒绝，仅返回当前计数语义下的可放行判断。
 * </p>
 * <p>
 * 拒绝时 retryAfter 按最老成员计算：其滑出窗口还需
 * {@code max(0, oldestScore + windowMillis - now)} 毫秒；阈值为 {@code null}（窗口空，
 * 一般意味着 permits 大于阈值）。阈值下调后无需迁移——窗口随成员自然滑出逐步排干。
 * </p>
 *
 * @author jay.wu
 */
public class SlidingWindowAlgorithm implements RateLimitAlgorithm {

    /**
     * 算法名（规则 algorithm 字段取值）
     */
    public static final String KEY = "sliding-window";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Class<?>[] requiredCapabilities() {
        return new Class<?>[]{ScoredWindowCapable.class};
    }

    @Override
    public RateLimitResult tryAcquire(RateLimitRule rule, KvStore store, String key,
                                      Object context, long nowMillis, int permits) {
        ScoredWindowCapable window = capabilityOf(store);
        SpaceKey spaceKey = SpaceKey.of(RateLimitEngine.DEFAULT_SPACE, key);
        long windowMillis = rule.getWindowMillis();
        long threshold = rule.getThreshold();

        Verdict verdict = window.offer(spaceKey, Offer.builder()
                .cutoffScore(nowMillis - windowMillis)
                .memberScore(nowMillis)
                .members(membersOf(nowMillis, permits))
                .maxCount((int) threshold)
                .ttlMillis(windowMillis)
                .build());

        // permits==0 为窥探：不添加成员，判断下一个单许可能否通过
        boolean allowed = permits == 0 ? verdict.getCount() < threshold : verdict.isAccepted();
        Long oldest = verdict.getOldestScore();
        Long retryAfter = oldest == null ? null
                : Math.max(0, oldest + windowMillis - nowMillis);
        return RateLimitResult.builder()
                .allowed(allowed)
                .ruleId(rule.getId())
                .remaining(Math.max(0, threshold - verdict.getCount()))
                .retryAfterMillis(retryAfter)
                .decisionTimeMillis(nowMillis)
                .reason(allowed ? RateLimitReason.PASS : RateLimitReason.THRESHOLD)
                .build();
    }

    private ScoredWindowCapable capabilityOf(KvStore store) {
        ScoredWindowCapable window = KvStores.capabilityOf(store, ScoredWindowCapable.class);
        if (window == null) {
            // 加载期已校验能力，此处为防御性兜底（如运行中换库）
            throw new RateLimitConfigException("Rate limit store not scored window capable|algorithm=" + key());
        }
        return window;
    }

    /**
     * 生成 permits 个唯一成员 id：nowMillis-hexRandom-i
     */
    private static List<String> membersOf(long nowMillis, int permits) {
        List<String> members = new ArrayList<>(permits);
        for (int i = 0; i < permits; i++) {
            members.add(nowMillis + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong()) + "-" + i);
        }
        return members;
    }
}
