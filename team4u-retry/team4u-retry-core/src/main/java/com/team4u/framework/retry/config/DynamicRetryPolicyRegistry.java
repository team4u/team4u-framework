package com.team4u.framework.retry.config;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.retry.api.RetryPolicy;
import lombok.Getter;

/**
 * 动态配置重试策略注册表
 * <p>
 * 基于配置中心前缀为 "retry.policy." 的配置进行动态更新。
 *
 * @author jay.wu
 */
public class DynamicRetryPolicyRegistry {

    private static final String DEFAULT_PREFIX = "retry.policy.*";

    /**
     * 注册表实例
     */
    @Getter
    private static ConfigDrivenRegistry<RetryPolicy> registry = new ConfigDrivenRegistry<>(
            ConfigManager.global(),
            DEFAULT_PREFIX,
            RetryPolicyParser::create
    );

    /**
     * 替换全局注册表实例，供测试或启动期装配使用。
     */
    public static void setRegistry(ConfigDrivenRegistry<RetryPolicy> registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        DynamicRetryPolicyRegistry.registry = registry;
    }

    /**
     * 重置注册表，仅用于测试
     */
    public static void reset() {
        registry = new ConfigDrivenRegistry<>(
                ConfigManager.global(),
                DEFAULT_PREFIX,
                RetryPolicyParser::create
        );
    }

    /**
     * 根据策略标识获取重试策略
     *
     * @param policyId 策略标识（支持短标识如 "db-retry" 或完整前缀键如 "retry.policy.db-retry"）
     * @return 重试策略实例，若不存在则返回 null
     */
    public static RetryPolicy getPolicy(String policyId) {
        return registry.get(policyId);
    }
}
