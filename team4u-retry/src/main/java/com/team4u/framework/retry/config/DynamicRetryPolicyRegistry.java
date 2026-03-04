package com.team4u.framework.retry.config;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.retry.RetryPolicy;

/**
 * 动态配置驱动的重试策略注册表
 * <p>
 * 监听配置中心中前缀为 "retry.policy." 的配置变更。
 * 例如配置：retry.policy.pay-notify = {"maxAttempts": 5, "backoffType": "exponentialJitter", ...}
 *
 * @author jay.wu
 */
public class DynamicRetryPolicyRegistry {

    private static final String DEFAULT_PREFIX = "retry.policy.";

    private static ConfigDrivenRegistry<RetryPolicy> registry = new ConfigDrivenRegistry<>(
            ConfigManager.global(),
            DEFAULT_PREFIX,
            RetryPolicyFactory::create
    );

    /**
     * 【仅供测试使用】获取当前注册表实例
     */
    public static ConfigDrivenRegistry<RetryPolicy> getRegistry() {
        return registry;
    }

    /**
     * 【仅供测试使用】注入自定义的注册表以适配测试上下文
     */
    public static void setRegistry(ConfigDrivenRegistry<RetryPolicy> newRegistry) {
        registry = newRegistry;
    }

    /**
     * 【仅供测试使用】将注册表重置为默认配置驱动状态
     */
    public static void reset() {
        registry = new ConfigDrivenRegistry<>(
                ConfigManager.global(),
                DEFAULT_PREFIX,
                RetryPolicyFactory::create
        );
    }

    /**
     * 根据策略 ID 获取最新的重试策略映射
     *
     * @param policyId 策略 ID（不含前缀）
     * @return 最新的 RetryPolicy 实例，如果配置不存在则返回 null
     */
    public static RetryPolicy getPolicy(String policyId) {
        return registry.get(DEFAULT_PREFIX + policyId);
    }
}
