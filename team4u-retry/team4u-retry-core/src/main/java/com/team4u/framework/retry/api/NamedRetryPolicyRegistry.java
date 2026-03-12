package com.team4u.framework.retry.api;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 重试策略创建注册表
 * <p>
 * 提供全局策略的注册与共享，采用无锁读取设计。
 */
public class NamedRetryPolicyRegistry extends KeyedPolicyRegistry<String, NamedRetryPolicyFactory> {

    private static final NamedRetryPolicyRegistry INSTANCE = new NamedRetryPolicyRegistry();

    public NamedRetryPolicyRegistry() {
        super(NamedRetryPolicyFactory.class);
    }

    /**
     * 获取全局注册表单例
     *
     * @return 注册表单例
     */
    public static NamedRetryPolicyRegistry global() {
        return INSTANCE;
    }
}