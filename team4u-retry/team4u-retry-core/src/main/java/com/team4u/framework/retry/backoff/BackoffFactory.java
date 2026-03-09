package com.team4u.framework.retry.backoff;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.retry.Backoff;
import com.team4u.framework.retry.config.BackoffConfig;

/**
 * 退避策略工厂
 *
 * @author jay.wu
 */
public interface BackoffFactory extends KeyedPolicy<String> {
    
    /**
     * 根据配置创建退避策略实例
     *
     * @param config 退避配置
     * @return 退避策略实例
     */
    Backoff create(BackoffConfig config);
}
