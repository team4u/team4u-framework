package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.ratelimiter.api.RateLimitConfigException;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.config.RateLimitRule;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.Data;

/**
 * 令牌桶算法
 * <p>
 * 参数语义：{@code threshold} = 桶容量（最大突发量），{@code windowMillis} = 注满一桶
 * 所需时间，即补充速率为 {@code capacity / windowMillis} 个令牌每毫秒。
 * 桶状态（JSON：{@code {"tokens":double,"lastMillis":long}}）存于键值存储的字符串值域，
 * 补水后按需扣减，经 {@link CasCapable#compareAndSet}（期望值为旧值字符串）原子提交，
 * CAS 失败（并发竞争）重读重试，最多 {@link #MAX_CAS_ATTEMPTS} 次，耗尽抛
 * {@link KvStoreException} 交由引擎按 failOpen 处置。
 * </p>
 * <p>
 * 键卫生：记录过期时间设为 {@code now + 2*windowMillis}——静默桶（长期无流量）
 * 自动回收，重新访问时按满桶起算。拒绝路径不回写状态（补水额由 lastMillis 推导，
 * 不写不丢）；时间回拨时补水量按 0 处理。
 * </p>
 *
 * @author jay.wu
 */
public class TokenBucketAlgorithm implements RateLimitAlgorithm {

    /**
     * 算法名（规则 algorithm 字段取值）
     */
    public static final String KEY = "token-bucket";

    /**
     * CAS 重试上限（超过视为存储竞争异常，走引擎 failOpen 路径）
     */
    public static final int MAX_CAS_ATTEMPTS = 8;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Class<?>[] requiredCapabilities() {
        return new Class<?>[]{CasCapable.class};
    }

    @Override
    public RateLimitResult tryAcquire(RateLimitRule rule, KvStore store, String key,
                                      Object context, long nowMillis, int permits) {
        CasCapable cas = capabilityOf(store);
        SpaceKey spaceKey = SpaceKey.of(RateLimitEngine.DEFAULT_SPACE, key);
        double capacity = rule.getThreshold();
        double rate = capacity / rule.getWindowMillis();

        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            KvRecord record = store.get(spaceKey);
            if (record == null) {
                // 新桶：满桶起算扣减构造初始状态，IF_ABSENT 防并发重复建桶
                double initialTokens = capacity - permits;
                if (initialTokens < 0) {
                    return denied(rule, nowMillis, capacity,
                            retryAfterMillis(permits - capacity, rate));
                }
                BucketState state = new BucketState();
                state.setTokens(initialTokens);
                state.setLastMillis(nowMillis);
                boolean created = store.put(spaceKey,
                        recordOf(state, rule, nowMillis), PutMode.IF_ABSENT);
                if (created) {
                    return allowed(rule, nowMillis, initialTokens);
                }
                continue;
            }

            BucketState state = parse(record.getValue(), key);
            double elapsed = Math.max(0, nowMillis - state.getLastMillis());
            double tokens = Math.min(capacity, state.getTokens() + elapsed * rate);
            if (tokens >= permits) {
                double nextTokens = tokens - permits;
                BucketState updated = new BucketState();
                updated.setTokens(nextTokens);
                updated.setLastMillis(nowMillis);
                boolean swapped = cas.compareAndSet(spaceKey, record.getValue(),
                        recordOf(updated, rule, nowMillis));
                if (swapped) {
                    return allowed(rule, nowMillis, nextTokens);
                }
                continue;
            }
            // 拒绝：不回写（补水额由 lastMillis 推导，不写不丢）
            return denied(rule, nowMillis, tokens, retryAfterMillis(permits - tokens, rate));
        }
        throw new KvStoreException("Token bucket CAS contention exhausted|key=" + spaceKey);
    }

    // ------------------------------------------------- 状态与工具

    private CasCapable capabilityOf(KvStore store) {
        CasCapable cas = KvStores.capabilityOf(store, CasCapable.class);
        if (cas == null) {
            // 加载期已校验能力，此处为防御性兜底（如运行中换库）
            throw new RateLimitConfigException("Rate limit store not cas capable|algorithm=" + key());
        }
        return cas;
    }

    /**
     * 桶状态记录：值 = 状态 JSON，过期 = now + 2*windowMillis（键卫生，静默桶自动回收）
     */
    private static KvRecord recordOf(BucketState state, RateLimitRule rule, long nowMillis) {
        return KvRecord.of(JsonUtil.toJsonStr(state), 2L * rule.getWindowMillis(), nowMillis);
    }

    private static BucketState parse(String value, String key) {
        BucketState state = JsonUtil.toBean(value, BucketState.class);
        if (state == null) {
            throw new KvStoreException("Invalid token bucket state|key=" + key + "|value=" + value);
        }
        return state;
    }

    private static long retryAfterMillis(double deficit, double rate) {
        return rate <= 0 ? 0 : (long) Math.ceil(deficit / rate);
    }

    private static RateLimitResult allowed(RateLimitRule rule, long nowMillis, double tokens) {
        return RateLimitResult.builder()
                .allowed(true)
                .ruleId(rule.getId())
                .remaining((long) tokens)
                .decisionTimeMillis(nowMillis)
                .reason(RateLimitReason.PASS)
                .build();
    }

    private static RateLimitResult denied(RateLimitRule rule, long nowMillis,
                                          double tokens, long retryAfterMillis) {
        return RateLimitResult.builder()
                .allowed(false)
                .ruleId(rule.getId())
                .remaining((long) tokens)
                .retryAfterMillis(retryAfterMillis)
                .decisionTimeMillis(nowMillis)
                .reason(RateLimitReason.THRESHOLD)
                .build();
    }

    /**
     * 令牌桶状态（JSON 持久化）：剩余令牌数与最近补水时刻
     */
    @Data
    public static class BucketState {

        /**
         * 剩余令牌数（浮点，按毫秒速率连续补充）
         */
        private double tokens;

        /**
         * 最近一次状态更新时刻（epoch 毫秒），补水量的推算基准
         */
        private long lastMillis;
    }
}
