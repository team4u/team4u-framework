package com.team4u.framework.retry.config;

import cn.hutool.core.util.ClassUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backoff.Backoff;

/**
 * 重试策略工厂
 * <p>
 * 将配置模型转换为重试策略实例。
 *
 * @author jay.wu
 */
public class RetryPolicyFactory {

    private static final Log log = LogFactory.get();

    /**
     * 从 JSON 配置创建重试策略
     *
     * @param jsonConfig JSON 格式配置
     * @return 重试策略实例
     */
    public static RetryPolicy create(String jsonConfig) {
        RetryPolicyConfig config = JSONUtil.toBean(jsonConfig, RetryPolicyConfig.class);
        RetryPolicy.Builder builder = RetryPolicy.builder()
                .maxAttempts(config.getMaxAttempts())
                .condition(config.getCondition());

        if (config.getLocalAttempts() != null) {
            builder.localAttempts(config.getLocalAttempts());
        }

        String type = config.getBackoffType() == null ? "fixed" : config.getBackoffType().toLowerCase();
        switch (type) {
            case "increment":
                builder.backoff(Backoff.increment(config.getInitialDelay(), (long) config.getMultiplier()));
                break;
            case "exponential":
                builder.backoff(Backoff.exponential(config.getInitialDelay(), config.getMultiplier(), config.getMaxDelay()));
                break;
            case "exponentialjitter":
                builder.backoff(Backoff.exponentialJitter(config.getInitialDelay(), config.getMultiplier(), config.getMaxDelay()));
                break;
            case "fixed":
            default:
                builder.backoff(Backoff.fixed(config.getInitialDelay()));
                break;
        }

        if (config.getRetryOnExceptions() != null) {
            for (String className : config.getRetryOnExceptions()) {
                addExceptionToBuilder(builder, className, true);
            }
        }

        if (config.getAbortOnExceptions() != null) {
            for (String className : config.getAbortOnExceptions()) {
                addExceptionToBuilder(builder, className, false);
            }
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void addExceptionToBuilder(RetryPolicy.Builder builder, String className, boolean isRetry) {
        try {
            Class<?> clazz = ClassUtil.loadClass(className);
            if (Throwable.class.isAssignableFrom(clazz)) {
                if (isRetry) {
                    builder.retryOn((Class<? extends Throwable>) clazz);
                } else {
                    builder.abortOn((Class<? extends Throwable>) clazz);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load retry policy exception class. className: {}. Please check config spelling.",
                    className, e);
        }
    }
}
