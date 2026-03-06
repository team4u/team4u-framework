package com.team4u.framework.retry.proxy;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 重试策略注册表
 * <p>
 * 提供全局策略的注册与共享，采用无锁读取设计。
 */
public class RetryPolicyRegistry extends KeyedPolicyRegistry<String, NamedRetryPolicy> {

    private static final RetryPolicyRegistry INSTANCE = new RetryPolicyRegistry();

    public RetryPolicyRegistry() {
        super(NamedRetryPolicy.class);
    }

    /**
     * 获取全局注册表单例
     *
     * @return 注册表单例
     */
    public static RetryPolicyRegistry global() {
        return INSTANCE;
    }
}
