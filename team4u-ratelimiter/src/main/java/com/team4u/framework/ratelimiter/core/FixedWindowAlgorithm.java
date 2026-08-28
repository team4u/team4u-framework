package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.ratelimiter.api.RateLimitConfigException;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.config.RateLimitRule;

/**
 * 固定窗口算法（浮窗语义）
 * <p>
 * 基于 {@link CounterCapable#incrementAndGet} 原子计数：窗口内第 N 次请求
 * {@code n = incrementAndGet(key, permits, windowMillis)}，{@code n <= threshold} 放行。
 * 请求超出阈值时 remaining 归零展示，浮窗无法精确给出重试等待（retryAfter 恒为 null）。
 * </p>
 * <p>
 * <b>窗口为浮动窗口</b>：TTL 自本窗口首次递增起算（CounterCapable 契约：TTL 键创建时
 * 设置、后续递增不刷新），即窗口起算点是「本窗口第一个请求」而非墙钟对齐时刻。
 * 与 history-window 的 epoch 对齐固定窗口不同，适合粗粒度配额控制。
 * </p>
 *
 * @author jay.wu
 */
public class FixedWindowAlgorithm implements RateLimitAlgorithm {

    /**
     * 算法名（规则 algorithm 字段取值）
     */
    public static final String KEY = "fixed-window";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Class<?>[] requiredCapabilities() {
        return new Class<?>[]{CounterCapable.class};
    }

    @Override
    public RateLimitResult tryAcquire(RateLimitRule rule, Object config, KvStore store, String key,
                                      Object context, long nowMillis, int permits) {
        CounterCapable counter = capabilityOf(store);
        long n = counter.incrementAndGet(
                SpaceKey.of(RateLimitEngine.DEFAULT_SPACE, key), permits, rule.getWindowMillis());
        long threshold = rule.getThreshold();
        boolean allowed = n <= threshold;
        return RateLimitResult.builder()
                .allowed(allowed)
                .ruleId(rule.getId())
                .remaining(Math.max(0, threshold - n))
                .decisionTimeMillis(nowMillis)
                .reason(allowed ? RateLimitReason.PASS : RateLimitReason.THRESHOLD)
                .build();
    }

    private CounterCapable capabilityOf(KvStore store) {
        CounterCapable counter = KvStores.capabilityOf(store, CounterCapable.class);
        if (counter == null) {
            // 加载期已校验能力，此处为防御性兜底（如运行中换库）
            throw new RateLimitConfigException("Rate limit store not counter capable|algorithm=" + key());
        }
        return counter;
    }
}
