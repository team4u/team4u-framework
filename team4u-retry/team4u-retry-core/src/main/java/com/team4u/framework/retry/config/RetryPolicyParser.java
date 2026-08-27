package com.team4u.framework.retry.config;

import com.team4u.framework.base.util.ClassUtil;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoff;
import com.team4u.framework.retry.common.backoff.BackoffRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
        RetryPolicyConfig config = JsonUtil.toBean(jsonConfig, RetryPolicyConfig.class);

        // 初始化策略构建器并配置基础重试次数与条件
        // 设置前台最大重试次数（如果配置存在）
        Integer foregroundMaxRetries = config.getForegroundMaxRetries();

        // 处理退避策略配置，若未配置则使用默认退避规则
        BackoffConfig backoffCfg = config.getBackoff();
        if (backoffCfg == null) {
            backoffCfg = new BackoffConfig();
        }

        // 该校验先于异常类加载，保持既有错误优先级
        Backoff backoff = BackoffRegistry.global().createBackoff(backoffCfg);

        // 注册需要通过重试来处理的异常类型
        Set<Class<? extends Throwable>> retryOnExceptions =
                loadExceptionClasses(config.getRetryOnExceptions(), "retryOnExceptions");

        // 注册需要立即停止重试的异常类型
        Set<Class<? extends Throwable>> abortOnExceptions =
                loadExceptionClasses(config.getAbortOnExceptions(), "abortOnExceptions");

        return RetryPolicy.builder()
                .maxRetries(config.getMaxRetries())
                .condition(config.getCondition())
                .foregroundMaxRetries(foregroundMaxRetries)
                .backoff(backoff)
                .retryOnExceptions(retryOnExceptions)
                .abortOnExceptions(abortOnExceptions)
                .build();
    }

    /**
     * 将配置中的异常类名加载并验证为异常类型集合
     *
     * @param classNames 异常类全限定名集合，允许为 {@code null}
     * @param fieldName  对应的配置字段名（用于报错提示）
     * @return 已验证的异常类型集合，配置为 {@code null} 或空集合时返回空集合
     */
    private static Set<Class<? extends Throwable>> loadExceptionClasses(
            Collection<String> classNames,
            String fieldName) {
        if (classNames == null) {
            return Collections.emptySet();
        }

        Set<Class<? extends Throwable>> classes = new HashSet<>();
        for (String className : classNames) {
            try {
                Class<?> clazz = ClassUtil.loadClass(className);
                if (!Throwable.class.isAssignableFrom(clazz)) {
                    throw new IllegalArgumentException(
                            "Invalid retry policy config. " + fieldName + " contains non-Throwable class: " + className);
                }
                classes.add(clazz.asSubclass(Throwable.class));
            } catch (Exception e) {
                if (e instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) e;
                }
                throw new IllegalArgumentException(
                        "Invalid retry policy config. Failed to load " + fieldName + " class: " + className, e);
            }
        }
        return classes;
    }
}
