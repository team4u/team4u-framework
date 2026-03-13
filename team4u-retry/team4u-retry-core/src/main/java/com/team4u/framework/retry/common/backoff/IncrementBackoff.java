package com.team4u.framework.retry.common.backoff;

import com.team4u.framework.base.util.ConvertUtil;
import com.team4u.framework.retry.config.BackoffConfig;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 等差递增延迟退避策略
 *
 * @author jay.wu
 */
@EqualsAndHashCode
public class IncrementBackoff implements Backoff {

    private final long initialDelayMillis;
    private final long stepMillis;

    public IncrementBackoff(long initialDelayMillis, long stepMillis) {
        if (initialDelayMillis < 0L) {
            throw new IllegalArgumentException("initialDelayMillis must be greater than or equal to 0");
        }
        if (stepMillis < 0L) {
            throw new IllegalArgumentException("stepMillis must be greater than or equal to 0");
        }
        this.initialDelayMillis = initialDelayMillis;
        this.stepMillis = stepMillis;
    }

    @Override
    public long calculateMillis(int attempt) {
        Backoff.validateAttempt(attempt);
        return initialDelayMillis + (attempt - 1L) * stepMillis;
    }

    /**
     * 等差递增延迟退避策略工厂
     */
    public static class Factory implements BackoffFactory {
        @Override
        public String key() {
            return "increment";
        }

        @Override
        public Backoff create(BackoffConfig config) {
            Map<String, Object> params = config.getParams();
            long initialDelay = 1000L;
            long stepMillis = 1000L;
            if (params != null) {
                initialDelay = ConvertUtil.toLong(params.get("initialDelay"), 1000L);
                stepMillis = ConvertUtil.toLong(params.get("stepMillis"), 1000L);
            }
            return new IncrementBackoff(initialDelay, stepMillis);
        }
    }
}
