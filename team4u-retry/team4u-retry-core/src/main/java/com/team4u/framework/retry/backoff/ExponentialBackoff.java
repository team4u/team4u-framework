package com.team4u.framework.retry.backoff;

import cn.hutool.core.convert.Convert;
import com.team4u.framework.retry.config.BackoffConfig;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 指数级退避策略
 *
 * @author jay.wu
 */
@EqualsAndHashCode
public class ExponentialBackoff implements Backoff {

    private final long initialDelayMillis;
    private final double multiplier;
    private final long maxDelayMillis;

    public ExponentialBackoff(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        if (initialDelayMillis < 0L) {
            throw new IllegalArgumentException("initialDelayMillis must be greater than or equal to 0");
        }
        if (multiplier <= 0D) {
            throw new IllegalArgumentException("multiplier must be greater than 0");
        }
        if (maxDelayMillis < initialDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must be greater than or equal to initialDelayMillis");
        }
        this.initialDelayMillis = initialDelayMillis;
        this.multiplier = multiplier;
        this.maxDelayMillis = maxDelayMillis;
    }

    @Override
    public long calculateMillis(int attempt) {
        Backoff.validateAttempt(attempt);
        long delay = (long) (initialDelayMillis * Math.pow(multiplier, attempt - 1));
        return Math.min(delay, maxDelayMillis);
    }

    /**
     * 指数级退避策略工厂
     */
    public static class Factory implements BackoffFactory {
        @Override
        public String key() {
            return "exponential";
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
            return new ExponentialBackoff(initialDelay, multiplier, maxDelay);
        }
    }
}
