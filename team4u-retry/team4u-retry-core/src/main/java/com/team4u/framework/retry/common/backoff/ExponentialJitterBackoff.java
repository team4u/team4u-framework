package com.team4u.framework.retry.common.backoff;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.retry.config.BackoffConfig;
import lombok.EqualsAndHashCode;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 带随机抖动（Jitter）的指数级退避策略。
 * <p>
 * 当前实现使用固定下界的随机区间算法：每次返回值都落在
 * [{@code initialDelayMillis}, 当前指数退避上界] 之间。
 * 这不是 equal jitter，也不是 decorrelated jitter。
 *
 * @author jay.wu
 */
@EqualsAndHashCode
public class ExponentialJitterBackoff implements Backoff {

    /**
     * 底层的指数退避策略，用于计算延迟上限
     */
    private final ExponentialBackoff exponentialBackoff;
    /**
     * 初始延迟时间（毫秒），同时也作为随机抖动的下界
     */
    private final long initialDelayMillis;

    /**
     * 构造带随机抖动的指数级退避策略
     *
     * @param initialDelayMillis 初始延迟时间（毫秒）
     * @param multiplier         指数倍数
     * @param maxDelayMillis     最大延迟时间（毫秒）
     */
    public ExponentialJitterBackoff(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        this.exponentialBackoff = new ExponentialBackoff(initialDelayMillis, multiplier, maxDelayMillis);
        this.initialDelayMillis = initialDelayMillis;
    }

    @Override
    public long calculateMillis(int attempt) {
        Backoff.validateAttempt(attempt);
        long maxCalculatedDelay = exponentialBackoff.calculateMillis(attempt);
        if (maxCalculatedDelay <= initialDelayMillis) {
            return maxCalculatedDelay;
        }
        // 固定下界随机区间：[initialDelayMillis, maxCalculatedDelay]
        return ThreadLocalRandom.current().nextLong(initialDelayMillis, maxCalculatedDelay + 1L);
    }

    @Override
    public BackoffConfig toConfig() {
        BackoffConfig config = exponentialBackoff.toConfig();
        config.setType("exponentialJitter");
        return config;
    }

    /**
     * 带随机抖动的指数级退避策略工厂
     */
    public static class Factory implements BackoffFactory {
        @Override
        public String key() {
            return "exponentialJitter";
        }

        @Override
        public Backoff create(BackoffConfig config) {
            Map<String, Object> params = config.getParams();
            long initialDelay = 1000L;
            double multiplier = 2.0;
            long maxDelay = 30000L;
            if (params != null) {
                initialDelay = ConvertUtil.toLong(params.get("initialDelay"), 1000L);
                multiplier = ConvertUtil.toDouble(params.get("multiplier"), 2.0);
                maxDelay = ConvertUtil.toLong(params.get("maxDelay"), 30000L);
            }
            return new ExponentialJitterBackoff(initialDelay, multiplier, maxDelay);
        }
    }
}
