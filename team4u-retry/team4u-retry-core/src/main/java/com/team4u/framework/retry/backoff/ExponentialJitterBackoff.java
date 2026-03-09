package com.team4u.framework.retry.backoff;

import cn.hutool.core.convert.Convert;
import com.team4u.framework.retry.Backoff;
import com.team4u.framework.retry.config.BackoffConfig;
import lombok.EqualsAndHashCode;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 带随机抖动（Jitter）的指数级退避策略
 *
 * @author jay.wu
 */
@EqualsAndHashCode
public class ExponentialJitterBackoff implements Backoff {

    private final ExponentialBackoff exponentialBackoff;
    private final long initialDelayMillis;

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
        return ThreadLocalRandom.current().nextLong(initialDelayMillis, maxCalculatedDelay + 1L);
    }

    /**
     * 带随机抖动的指数级退避策略工厂
     */
    public static class Factory implements BackoffFactory {
        @Override
        public String key() {
            return "exponentialjitter";
        }

        @Override
        public Backoff create(BackoffConfig config) {
            Map<String, Object> params = config.getParams();
            long initialDelay = 1000L;
            double multiplier = 2.0;
            long maxDelay = 30000L;
            if (params != null) {
                initialDelay = Convert.toLong(params.get("initialDelay"), 1000L);
                multiplier = Convert.toDouble(params.get("multiplier"), 2.0);
                maxDelay = Convert.toLong(params.get("maxDelay"), 30000L);
            }
            return new ExponentialJitterBackoff(initialDelay, multiplier, maxDelay);
        }
    }
}
