package com.team4u.framework.retry.proxy;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.retry.RetryPolicy;

/**
 * 命名重试策略接口
 * <p>
 * 支持基于键值的策略注册与获取。
 */
public interface NamedRetryPolicy extends KeyedPolicy<String> {

    /**
     * 获取基础重试策略
     *
     * @return 重试策略实例
     */
    RetryPolicy getPolicy();
}
