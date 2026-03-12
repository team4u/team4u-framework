package com.team4u.framework.retry.config;

import cn.hutool.core.util.ClassUtil;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.common.backoff.BackoffRegistry;
import com.team4u.framework.retry.api.RetryPolicy;

/**
 * 重试策略工厂类
 * <p>
 * 负责将 {@link RetryPolicyConfig} 配置对象或 JSON 字符串解析并转换为可运行的 {@link RetryPolicy}
 * 实例。
 *
 * @author jay.wu
 */
public class RetryPolicyParser {

    /**
     * 从 JSON 配置创建重试策略
     *
     * @param jsonConfig JSON 格式配置
     * @return 重试策略实例
     */
    public static RetryPolicy create(String jsonConfig) {

        // 将 JSON 字符串解析为重试策略配置对象
        RetryPolicyConfig config = JSONUtil.toBean(jsonConfig, RetryPolicyConfig.class);

        // 初始化策略构建器并配置基础重试次数与条件
        RetryPolicy.Builder builder = RetryPolicy.builder()
                .maxRetries(config.getMaxRetries())
                .condition(config.getCondition());

        // 设置前台最大重试次数（如果配置存在）
        if (config.getForegroundMaxRetries() != null) {
            builder.foregroundMaxRetries(config.getForegroundMaxRetries());
        }

        // 处理退避策略配置，若未配置则使用默认退避规则
        BackoffConfig backoffCfg = config.getBackoff();
        if (backoffCfg == null) {
            backoffCfg = new BackoffConfig();
        }

        // 根据配置从注册中心创建对应的退避实例并关联到策略中
        builder.backoff(BackoffRegistry.global().createBackoff(backoffCfg));

        // 注册需要通过重试来处理的异常类型
        if (config.getRetryOnExceptions() != null) {
            for (String className : config.getRetryOnExceptions()) {
                addExceptionToBuilder(builder, className, "retryOnExceptions", true);
            }
        }

        // 注册需要立即停止重试的异常类型
        if (config.getAbortOnExceptions() != null) {
            for (String className : config.getAbortOnExceptions()) {
                addExceptionToBuilder(builder, className, "abortOnExceptions", false);
            }
        }

        return builder.build();
    }

    /**
     * 将异常类名加载并添加到构建器中
     *
     * @param builder   重试策略构建器
     * @param className 异常类全限定名
     * @param fieldName 对应的配置字段名（用于报错提示）
     * @param isRetry   标识是否为重试异常（true 为重试，false 为中止）
     */
    @SuppressWarnings("unchecked")
    private static void addExceptionToBuilder(
            RetryPolicy.Builder builder,
            String className,
            String fieldName,
            boolean isRetry) {
        try {
            // 加载异常类
            Class<?> clazz = ClassUtil.loadClass(className);
            // 验证加载的类是否继承自 Throwable
            if (!Throwable.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException(
                        "Invalid retry policy config. " + fieldName + " contains non-Throwable class: " + className);
            }
            // 根据类型分别注册到构建器中
            if (isRetry) {
                builder.retryOn((Class<? extends Throwable>) clazz);
            } else {
                builder.abortOn((Class<? extends Throwable>) clazz);
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            // 异常类加载失败或解析出错时统一抛出非法参数异常
            throw new IllegalArgumentException(
                    "Invalid retry policy config. Failed to load " + fieldName + " class: " + className, e);
        }
    }
}
