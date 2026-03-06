package com.team4u.framework.retry.recovery;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 全局恢复处理器注册表
 */
public class RecoveryHandlerRegistry extends KeyedPolicyRegistry<String, RecoveryHandler> {

    private static final RecoveryHandlerRegistry INSTANCE = new RecoveryHandlerRegistry();
    private static final Object DEFAULT_PROXY_RECOVERY_MONITOR = new Object();

    public RecoveryHandlerRegistry() {
        super(RecoveryHandler.class);
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
     * 确保默认的注解快照恢复处理器已注册
     */
    public static void ensureDefaultProxyRecoveryHandlerRegistered() {
        global().registerDefaultProxyRecoveryHandler();
    }

    /**
     * 幂等注册默认的注解快照恢复处理器
     */
    public void registerDefaultProxyRecoveryHandler() {
        if (get(RetryTaskTypes.DEFAULT_PROXY_RECOVERY).isPresent()) {
            return;
        }
        synchronized (DEFAULT_PROXY_RECOVERY_MONITOR) {
            if (get(RetryTaskTypes.DEFAULT_PROXY_RECOVERY).isPresent()) {
                return;
            }
            register(new SnapshotRecoveryHandler(RetryTaskTypes.DEFAULT_PROXY_RECOVERY));
        }
    }
}
