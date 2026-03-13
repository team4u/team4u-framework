package com.team4u.framework.retry.common.backoff;

import com.team4u.framework.base.convert.ConvertUtil;
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

    /**
     * 固定延迟时间（毫秒）
     */
    private final long delayMillis;

    /**
     * 构造固定延迟退避策略
     *
     * @param delayMillis 固定延迟时间（毫秒）
     */
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
                delay = ConvertUtil.toLong(params.get("delay"), ConvertUtil.toLong(params.get("initialDelay"), 1000L));
            }
            return new FixedBackoff(delay);
        }
    }
}
