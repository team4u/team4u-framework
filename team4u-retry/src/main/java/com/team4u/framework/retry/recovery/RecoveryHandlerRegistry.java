package com.team4u.framework.retry.recovery;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 全局恢复处理器注册表
 */
public class RecoveryHandlerRegistry extends KeyedPolicyRegistry<String, RecoveryHandler> {

    private static final RecoveryHandlerRegistry INSTANCE = new RecoveryHandlerRegistry();

    public RecoveryHandlerRegistry() {
        super(RecoveryHandler.class);
    }

    /**
     * 获取全局单例实例
     *
     * @return 注册表实例
     */
    public static RecoveryHandlerRegistry global() {
        return INSTANCE;
    }
}
