package com.team4u.framework.retry.recovery;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.util.PolicyScanner;

/**
 * 全局恢复处理器注册表
 */
public class RecoveryHandlerRegistry extends KeyedPolicyRegistry<String, RecoveryHandler<?>> {

    private static final RecoveryHandlerRegistry INSTANCE = new RecoveryHandlerRegistry();

    public RecoveryHandlerRegistry() {
        super((Class) RecoveryHandler.class);
        autoScan();
    }

    /**
     * 获取全局注册表单例
     *
     * @return 注册表单例实例
     */
    public static RecoveryHandlerRegistry global() {
        return INSTANCE;
    }

    /**
     * 通过 SPI 自动装配恢复处理器
     */
    public void autoScan() {
        PolicyScanner.registerFromServiceLoader(this);
    }
}
