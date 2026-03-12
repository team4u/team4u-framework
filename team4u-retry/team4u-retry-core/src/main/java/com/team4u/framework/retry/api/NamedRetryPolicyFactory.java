package com.team4u.framework.retry.api;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 重试策略创建接口
 * <p>
 * 支持基于键值的策略注册与获取。
 */
public interface NamedRetryPolicyFactory extends KeyedPolicy<String> {

    /**
     * 获取基础重试策略
     *
     * @return 重试策略实例
     */
    RetryPolicy create();
}