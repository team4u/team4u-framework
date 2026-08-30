package com.team4u.framework.retry.common.backoff;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.retry.config.BackoffConfig;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 等差递增延迟退避策略
 *
 * @author jay.wu
 */
@EqualsAndHashCode
public class IncrementBackoff implements Backoff {

    /**
     * 初始延迟时间（毫秒）
     */
    private final long initialDelayMillis;
    /**
     * 递增步长（毫秒）
     */
    private final long stepMillis;

    /**
     * 构造等差递增延迟退避策略
     *
     * @param initialDelayMillis 初始延迟时间（毫秒）
     * @param stepMillis         递增步长（毫秒）
     */
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

    @Override
    public BackoffConfig toConfig() {
        BackoffConfig config = new BackoffConfig();
        config.setType("increment");
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("initialDelay", initialDelayMillis);
        params.put("stepMillis", stepMillis);
        config.setParams(params);
        return config;
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
