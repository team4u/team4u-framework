package com.team4u.framework.retry.backoff;

import cn.hutool.core.convert.Convert;
import com.team4u.framework.retry.config.BackoffConfig;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 固定延迟退避策略
 *
 * @author jay.wu
 */
@EqualsAndHashCode
public class FixedBackoff implements Backoff {

    private final long delayMillis;

    public FixedBackoff(long delayMillis) {
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis must be greater than or equal to 0");
        }
        this.delayMillis = delayMillis;
    }

    @Override
    public long calculateMillis(int attempt) {
        Backoff.validateAttempt(attempt);
        return delayMillis;
    }

    /**
     * 固定延迟退避策略工厂
     */
    public static class Factory implements BackoffFactory {
        @Override
        public String key() {
            return "fixed";
        }

        @Override
        public Backoff create(BackoffConfig config) {
            Map<String, Object> params = config.getParams();
            long delay = 1000L;
            if (params != null) {
                delay = Convert.toLong(params.get("delay"), Convert.toLong(params.get("initialDelay"), 1000L));
            }
            return new FixedBackoff(delay);
        }
    }
}
