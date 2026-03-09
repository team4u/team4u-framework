package com.team4u.framework.retry.policy;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 重试策略创建注册表
 * <p>
 * 提供全局策略的注册与共享，采用无锁读取设计。
 */
public class RetryPolicyFactoryRegistry extends KeyedPolicyRegistry<String, RetryPolicyFactory> {

    private static final RetryPolicyFactoryRegistry INSTANCE = new RetryPolicyFactoryRegistry();

    public RetryPolicyFactoryRegistry() {
        super(RetryPolicyFactory.class);
    }

    /**
     * 获取全局注册表单例
     *
     * @return 注册表单例
     */
    public static RetryPolicyFactoryRegistry global() {
        return INSTANCE;
    }
}