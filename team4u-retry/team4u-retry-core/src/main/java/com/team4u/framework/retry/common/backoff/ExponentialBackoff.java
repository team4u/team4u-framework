package com.team4u.framework.retry.common.backoff;

import com.team4u.framework.base.convert.ConvertUtil;
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

    /**
     * 初始延迟时间（毫秒）
     */
    private final long initialDelayMillis;
    /**
     * 指数倍数
     */
    private final double multiplier;
    /**
     * 最大延迟时间（毫秒）
     */
    private final long maxDelayMillis;

    /**
     * 构造指数级退避策略
     *
     * @param initialDelayMillis 初始延迟时间（毫秒）
     * @param multiplier         指数倍数，必须大于 0
     * @param maxDelayMillis     最大延迟时间（毫秒）
     */
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
        double calculatedDelay = initialDelayMillis * Math.pow(multiplier, attempt - 1);
        if (Double.isNaN(calculatedDelay) || Double.isInfinite(calculatedDelay) || calculatedDelay >= maxDelayMillis) {
            return maxDelayMillis;
        }
        return (long) calculatedDelay;
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
                initialDelay = ConvertUtil.toLong(params.get("initialDelay"), 1000L);
                multiplier = ConvertUtil.toDouble(params.get("multiplier"), 2.0);
                maxDelay = ConvertUtil.toLong(params.get("maxDelay"), 30000L);
            }
            return new ExponentialBackoff(initialDelay, multiplier, maxDelay);
        }
    }
}
