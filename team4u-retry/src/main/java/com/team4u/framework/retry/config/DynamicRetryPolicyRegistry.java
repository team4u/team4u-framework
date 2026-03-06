package com.team4u.framework.retry.config;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.retry.RetryPolicy;
import lombok.Getter;
import lombok.Setter;

/**
 * 动态配置重试策略注册表
 * <p>
 * 基于配置中心前缀为 "retry.policy." 的配置进行动态更新。
 *
 * @author jay.wu
 */
public class DynamicRetryPolicyRegistry {

    private static final String DEFAULT_PREFIX = "retry.policy.";

    /**
     * 注册表实例
     */
    @Getter
    @Setter
    private static ConfigDrivenRegistry<RetryPolicy> registry = new ConfigDrivenRegistry<>(
            ConfigManager.global(),
            DEFAULT_PREFIX,
            RetryPolicyFactory::create
    );

    /**
     * 重置注册表，仅用于测试
     */
    public static void reset() {
        registry = new ConfigDrivenRegistry<>(
                ConfigManager.global(),
                DEFAULT_PREFIX,
                RetryPolicyFactory::create
        );
    }

    /**
     * 根据策略标识获取重试策略
     *
     * @param policyId 策略标识（不含前缀）
     * @return 重试策略实例，若不存在则返回 null
     */
    public static RetryPolicy getPolicy(String policyId) {
        return registry.get(DEFAULT_PREFIX + policyId);
    }
}
