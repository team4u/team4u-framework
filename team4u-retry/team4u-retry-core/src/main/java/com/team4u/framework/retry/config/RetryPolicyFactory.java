package com.team4u.framework.retry.config;

import cn.hutool.core.util.ClassUtil;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.backoff.BackoffRegistry;
import com.team4u.framework.retry.policy.RetryPolicy;

/**
 * 重试策略工厂类
 * <p>
 * 负责将 {@link RetryPolicyConfig} 配置对象或 JSON 字符串解析并转换为可运行的 {@link RetryPolicy}
 * 实例。
 *
 * @author jay.wu
 */
public class RetryPolicyFactory {

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

        if (config.getForegroundAttempts() != null) {
            builder.foregroundAttempts(config.getForegroundAttempts());
        }

        BackoffConfig backoffCfg = config.getBackoff();
        if (backoffCfg == null) {
            backoffCfg = new BackoffConfig();
        }

        builder.backoff(BackoffRegistry.global().createBackoff(backoffCfg));

        if (config.getRetryOnExceptions() != null) {
            for (String className : config.getRetryOnExceptions()) {
                addExceptionToBuilder(builder, className, "retryOnExceptions", true);
            }
        }

        if (config.getAbortOnExceptions() != null) {
            for (String className : config.getAbortOnExceptions()) {
                addExceptionToBuilder(builder, className, "abortOnExceptions", false);
            }
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void addExceptionToBuilder(
            RetryPolicy.Builder builder,
            String className,
            String fieldName,
            boolean isRetry) {
        try {
            Class<?> clazz = ClassUtil.loadClass(className);
            if (!Throwable.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException(
                        "Invalid retry policy config. " + fieldName + " contains non-Throwable class: " + className);
            }
            if (isRetry) {
                builder.retryOn((Class<? extends Throwable>) clazz);
            } else {
                builder.abortOn((Class<? extends Throwable>) clazz);
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            throw new IllegalArgumentException(
                    "Invalid retry policy config. Failed to load " + fieldName + " class: " + className, e);
        }
    }
}
