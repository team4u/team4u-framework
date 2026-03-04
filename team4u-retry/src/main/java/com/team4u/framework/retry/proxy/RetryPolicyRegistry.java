package com.team4u.framework.retry.proxy;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 重试策略注册表，用于在运行时全局共享策略，基于无锁读取设计。
 */
public class RetryPolicyRegistry extends KeyedPolicyRegistry<String, NamedRetryPolicy> {

    private static final RetryPolicyRegistry INSTANCE = new RetryPolicyRegistry();

    public RetryPolicyRegistry() {
        super(NamedRetryPolicy.class);
    }

    /**
     * 获取全局注册表单例
     *
     * @return 全局策略工厂注册管理实例
     */
    public static RetryPolicyRegistry global() {
        return INSTANCE;
    }
}
