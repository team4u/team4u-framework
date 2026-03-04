package com.team4u.framework.retry.config;

import cn.hutool.core.util.ClassUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backoff.Backoff;

/**
 * 重试策略工厂，用于将配置模型转换为不可变的策略实例
 *
 * @author jay.wu
 */
public class RetryPolicyFactory {

    private static final Log log = LogFactory.get();

    @SuppressWarnings("unchecked")
    public static RetryPolicy create(String jsonConfig) {
        RetryPolicyConfig config = JSONUtil.toBean(jsonConfig, RetryPolicyConfig.class);
        RetryPolicy.Builder builder = RetryPolicy.builder()
                .maxAttempts(config.getMaxAttempts())
                .condition(config.getCondition());

        // 解析 Backoff
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

        // 解析需要重试的异常类
        if (config.getRetryOnExceptions() != null) {
            for (String className : config.getRetryOnExceptions()) {
                addExceptionToBuilder(builder, className, true);
            }
        }

        // 解析需要终止重试的异常类
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
            log.error("加载重试策略异常类失败, className: {}, 请检查配置中心拼写!", className, e);
        }
    }
}
