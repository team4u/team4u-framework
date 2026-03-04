package com.team4u.framework.retry.proxy;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.retry.RetryPolicy;

/**
 * 带有名称标识的重试策略接口，用于支撑基于键值的策略仓库注册与获取。
 */
public interface NamedRetryPolicy extends KeyedPolicy<String> {

    /**
     * 返回基础的重试策略实例
     *
     * @return 实际的 RetryPolicy 实例
     */
    RetryPolicy getPolicy();
}
